//
//  DeveloperService.swift
//  ClawPhones
//

import Foundation
import Combine

@MainActor
final class DeveloperService: ObservableObject {
    static let shared = DeveloperService()

    @Published var apiKeys: [APIKey] = []
    @Published var webhooks: [WebhookConfig] = []
    @Published var usageRecords: [UsageRecord] = []
    @Published var plugins: [SDKPlugin] = []

    private let session: URLSession
    private let baseURLDefaultsKey = "ai.clawphones.api.url"
    private let tokenKeychainKey = "relay_token"

    private init(session: URLSession = .shared) {
        self.session = session
    }

    // MARK: - Error Types

    enum DeveloperError: LocalizedError {
        case invalidAPIURL(String)
        case missingToken
        case invalidResponse
        case http(statusCode: Int, message: String)
        case decodeFailed
        case invalidPermissions
        case keyNotFound

        var errorDescription: String? {
            switch self {
            case .invalidAPIURL(let url):
                return "Invalid API URL: \(url)"
            case .missingToken:
                return "Authentication token is missing."
            case .invalidResponse:
                return "Server returned an invalid response."
            case .http(let statusCode, let message):
                return "HTTP \(statusCode): \(message)"
            case .decodeFailed:
                return "Failed to decode server response."
            case .invalidPermissions:
                return "Insufficient permissions for this operation."
            case .keyNotFound:
                return "API key not found."
            }
        }
    }

    // MARK: - Request Builders

    private var apiURL: String {
        let raw = UserDefaults.standard.string(forKey: baseURLDefaultsKey) ?? ""
        return normalizedURL(from: raw) ?? "http://localhost:8787"
    }

    private var authToken: String? {
        KeychainHelper.shared.read(key: tokenKeychainKey)
    }

    private func endpointURL(path: String) throws -> URL {
        guard let base = normalizedURL(from: apiURL),
              let url = URL(string: "\(base)\(path)") else {
            throw DeveloperError.invalidAPIURL(apiURL)
        }
        return url
    }

    private func buildRequest(url: URL, method: String = "GET") throws -> URLRequest {
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.addValue("application/json", forHTTPHeaderField: "Accept")
        request.addValue("application/json", forHTTPHeaderField: "Content-Type")

        if let token = authToken {
            request.addValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        } else {
            throw DeveloperError.missingToken
        }

        return request
    }

    private func perform(_ request: URLRequest) async throws -> Data {
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw DeveloperError.invalidResponse
        }

        guard (200 ..< 300).contains(http.statusCode) else {
            let message: String
            if let text = String(data: data, encoding: .utf8), !text.isEmpty {
                message = text
            } else {
                message = HTTPURLResponse.localizedString(forStatusCode: http.statusCode)
            }
            throw DeveloperError.http(statusCode: http.statusCode, message: message)
        }

        return data
    }

    private func normalizedURL(from raw: String) -> String? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }

        var normalized = trimmed
        while normalized.hasSuffix("/") {
            normalized.removeLast()
        }

        guard let url = URL(string: normalized),
              let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https",
              url.host != nil else {
            return nil
        }

        return normalized
    }

    // MARK: - API Key Management

    /// Generate a new API key with specified permissions
    func generateAPIKey(
        name: String,
        permissions: [APIPermission],
        rateLimit: Int? = nil,
        expiresAt: Date? = nil
    ) async throws -> APIKey {
        let endpoint = try endpointURL(path: "/v1/developer/keys")
        var request = try buildRequest(url: endpoint, method: "POST")

        struct CreateKeyRequest: Codable {
            let name: String
            let permissions: [String]
            let rateLimit: Int?
            let expiresAt: Date?

            enum CodingKeys: String, CodingKey {
                case name
                case permissions
                case rateLimit = "rate_limit"
                case expiresAt = "expires_at"
            }
        }

        let body = CreateKeyRequest(
            name: name,
            permissions: permissions.map { $0.rawValue },
            rateLimit: rateLimit,
            expiresAt: expiresAt
        )

        request.httpBody = try JSONEncoder().encode(body)

        let data = try await perform(request)
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let apiKey = try decoder.decode(APIKey.self, from: data)

        apiKeys.append(apiKey)
        return apiKey
    }

    /// Revoke an existing API key
    func revokeKey(keyId: String) async throws {
        let endpoint = try endpointURL(path: "/v1/developer/keys/\(keyId)")
        let request = try buildRequest(url: endpoint, method: "DELETE")

        _ = try await perform(request)

        apiKeys.removeAll { $0.id == keyId }
    }

    /// List all API keys for the authenticated developer
    func listKeys() async throws {
        let endpoint = try endpointURL(path: "/v1/developer/keys")
        let request = try buildRequest(url: endpoint, method: "GET")

        let data = try await perform(request)
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let fetchedKeys = try decoder.decode([APIKey].self, from: data)

        apiKeys = fetchedKeys
    }

    // MARK: - Webhook Management

    /// Register a new webhook configuration
    func registerWebhook(
        url: String,
        events: [String],
        secret: String
    ) async throws -> WebhookConfig {
        let endpoint = try endpointURL(path: "/v1/developer/webhooks")
        var request = try buildRequest(url: endpoint, method: "POST")

        struct RegisterWebhookRequest: Codable {
            let url: String
            let events: [String]
            let secret: String
        }

        let body = RegisterWebhookRequest(
            url: url,
            events: events,
            secret: secret
        )

        request.httpBody = try JSONEncoder().encode(body)

        let data = try await perform(request)
        let webhook = try JSONDecoder().decode(WebhookConfig.self, from: data)

        webhooks.append(webhook)
        return webhook
    }

    /// Delete a webhook configuration
    func deleteWebhook(webhookId: String) async throws {
        let endpoint = try endpointURL(path: "/v1/developer/webhooks/\(webhookId)")
        let request = try buildRequest(url: endpoint, method: "DELETE")

        _ = try await perform(request)

        webhooks.removeAll { $0.id == webhookId }
    }

    /// Test a webhook by sending a test payload
    func testWebhook(webhookId: String) async throws -> Bool {
        let endpoint = try endpointURL(path: "/v1/developer/webhooks/\(webhookId)/test")
        let request = try buildRequest(url: endpoint, method: "POST")

        struct TestResponse: Codable {
            let success: Bool
            let message: String
        }

        let data = try await perform(request)
        let response = try JSONDecoder().decode(TestResponse.self, from: data)

        return response.success
    }

    /// List all registered webhooks
    func listWebhooks() async throws {
        let endpoint = try endpointURL(path: "/v1/developer/webhooks")
        let request = try buildRequest(url: endpoint, method: "GET")

        let data = try await perform(request)
        let fetchedWebhooks = try JSONDecoder().decode([WebhookConfig].self, from: data)

        webhooks = fetchedWebhooks
    }

    // MARK: - Usage Analytics

    /// Fetch usage records for a date range
    func fetchUsage(startDate: Date, endDate: Date) async throws {
        let formatter = ISO8601DateFormatter()
        let startStr = formatter.string(from: startDate)
        let endStr = formatter.string(from: endDate)
        let path = "/v1/developer/usage?start=\(startStr)&end=\(endStr)"

        let endpoint = try endpointURL(path: path)
        let request = try buildRequest(url: endpoint, method: "GET")

        let data = try await perform(request)
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let fetchedRecords = try decoder.decode([UsageRecord].self, from: data)

        usageRecords = fetchedRecords
    }

    /// Get aggregated usage statistics
    func fetchUsageStats() async throws -> DeveloperUsageStats {
        let endpoint = try endpointURL(path: "/v1/developer/usage/stats")
        let request = try buildRequest(url: endpoint, method: "GET")

        let data = try await perform(request)
        return try JSONDecoder().decode(DeveloperUsageStats.self, from: data)
    }

    // MARK: - Plugin Management

    /// Fetch all available SDK plugins
    func fetchPlugins() async throws {
        let endpoint = try endpointURL(path: "/v1/developer/plugins")
        let request = try buildRequest(url: endpoint, method: "GET")

        let data = try await perform(request)
        let fetchedPlugins = try JSONDecoder().decode([SDKPlugin].self, from: data)

        plugins = fetchedPlugins
    }

    /// Install a plugin by ID
    func installPlugin(pluginId: String) async throws {
        let endpoint = try endpointURL(path: "/v1/developer/plugins/\(pluginId)/install")
        let request = try buildRequest(url: endpoint, method: "POST")

        _ = try await perform(request)

        if let index = plugins.firstIndex(where: { $0.id == pluginId }) {
            plugins[index] = SDKPlugin(
                id: plugins[index].id,
                name: plugins[index].name,
                version: plugins[index].version,
                description: plugins[index].description,
                author: plugins[index].author,
                downloadURL: plugins[index].downloadURL,
                isInstalled: true
            )
        }
    }

    /// Uninstall a plugin by ID
    func uninstallPlugin(pluginId: String) async throws {
        let endpoint = try endpointURL(path: "/v1/developer/plugins/\(pluginId)/uninstall")
        let request = try buildRequest(url: endpoint, method: "DELETE")

        _ = try await perform(request)

        if let index = plugins.firstIndex(where: { $0.id == pluginId }) {
            plugins[index] = SDKPlugin(
                id: plugins[index].id,
                name: plugins[index].name,
                version: plugins[index].version,
                description: plugins[index].description,
                author: plugins[index].author,
                downloadURL: plugins[index].downloadURL,
                isInstalled: false
            )
        }
    }

    // MARK: - Convenience Methods

    func apiKey(withId id: String) -> APIKey? {
        apiKeys.first { $0.id == id }
    }

    func webhook(withId id: String) -> WebhookConfig? {
        webhooks.first { $0.id == id }
    }

    func plugin(withId id: String) -> SDKPlugin? {
        plugins.first { $0.id == id }
    }

    func hasPermission(_ permission: APIPermission, keyId: String) -> Bool {
        guard let apiKey = apiKey(withId: keyId) else {
            return false
        }
        return apiKey.permissions.contains(permission.rawValue)
    }

    func totalUsage() -> Int {
        usageRecords.reduce(0) { $0 + $1.count }
    }

    func averageLatency() -> Double {
        guard !usageRecords.isEmpty else { return 0.0 }
        let total = usageRecords.reduce(0.0) { $0 + Double($1.latencyMs) }
        return total / Double(usageRecords.count)
    }
}

// MARK: - Supporting Types

struct DeveloperUsageStats: Codable {
    let totalRequests: Int
    let averageLatency: Double
    let mostUsedEndpoint: String
    let periodStart: Date
    let periodEnd: Date

    enum CodingKeys: String, CodingKey {
        case totalRequests = "total_requests"
        case averageLatency = "average_latency"
        case mostUsedEndpoint = "most_used_endpoint"
        case periodStart = "period_start"
        case periodEnd = "period_end"
    }
}

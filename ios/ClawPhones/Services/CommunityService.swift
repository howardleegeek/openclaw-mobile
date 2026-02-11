//
//  CommunityService.swift
//  ClawPhones
//

import Foundation
import Combine

@MainActor
final class CommunityService: ObservableObject {
    static let shared = CommunityService()

    @Published var communities: [Community] = []
    @Published var activeCommunity: Community?
    @Published var communityAlerts: [AlertEvent] = []

    private let session: URLSession
    private let baseURLDefaultsKey = "ai.clawphones.api.url"
    private let tokenKeychainKey = "relay_token"

    private init(session: URLSession = .shared) {
        self.session = session
    }

    // MARK: - Error Types

    enum CommunityError: LocalizedError {
        case invalidAPIURL(String)
        case missingToken
        case invalidResponse
        case http(statusCode: Int, message: String)
        case decodeFailed

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
            throw CommunityError.invalidAPIURL(apiURL)
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
            throw CommunityError.missingToken
        }

        return request
    }

    private func perform(_ request: URLRequest) async throws -> Data {
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw CommunityError.invalidResponse
        }

        guard (200 ..< 300).contains(http.statusCode) else {
            let message: String
            if let text = String(data: data, encoding: .utf8), !text.isEmpty {
                message = text
            } else {
                message = HTTPURLResponse.localizedString(forStatusCode: http.statusCode)
            }
            throw CommunityError.http(statusCode: http.statusCode, message: message)
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

    // MARK: - API Methods

    /// Create a new community
    func createCommunity(
        name: String,
        description: String,
        centerLat: Double,
        centerLon: Double,
        h3Cells: [String],
        inviteCode: String
    ) async throws -> Community {
        let endpoint = try endpointURL(path: "/v1/communities")
        var request = try buildRequest(url: endpoint, method: "POST")

        struct CreateCommunityRequest: Codable {
            let name: String
            let description: String
            let centerLat: Double
            let centerLon: Double
            let h3Cells: [String]
            let inviteCode: String

            enum CodingKeys: String, CodingKey {
                case name
                case description
                case centerLat = "center_lat"
                case centerLon = "center_lon"
                case h3Cells = "h3_cells"
                case inviteCode = "invite_code"
            }
        }

        let body = CreateCommunityRequest(
            name: name,
            description: description,
            centerLat: centerLat,
            centerLon: centerLon,
            h3Cells: h3Cells,
            inviteCode: inviteCode
        )
        request.httpBody = try JSONEncoder().encode(body)

        let data = try await perform(request)
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let community = try decoder.decode(Community.self, from: data)

        communities.append(community)
        return community
    }

    /// Join a community by invite code
    func joinCommunity(inviteCode: String) async throws -> Community {
        let endpoint = try endpointURL(path: "/v1/communities/join")
        var request = try buildRequest(url: endpoint, method: "POST")

        struct JoinRequest: Codable {
            let inviteCode: String

            enum CodingKeys: String, CodingKey {
                case inviteCode = "invite_code"
            }
        }

        let body = JoinRequest(inviteCode: inviteCode)
        request.httpBody = try JSONEncoder().encode(body)

        let data = try await perform(request)
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let community = try decoder.decode(Community.self, from: data)

        communities.append(community)
        return community
    }

    /// Leave a community
    func leaveCommunity(communityId: String) async throws {
        let endpoint = try endpointURL(path: "/v1/communities/\(communityId)/leave")
        let request = try buildRequest(url: endpoint, method: "DELETE")

        _ = try await perform(request)

        communities.removeAll { $0.id == communityId }
        if activeCommunity?.id == communityId {
            activeCommunity = nil
        }
    }

    /// Fetch all communities the current user is a member of
    func fetchMyCommunities() async throws {
        let endpoint = try endpointURL(path: "/v1/communities/mine")
        let request = try buildRequest(url: endpoint, method: "GET")

        let data = try await perform(request)
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let fetchedCommunities = try decoder.decode([Community].self, from: data)

        communities = fetchedCommunities
    }

    /// Fetch alerts for a specific community
    func fetchCommunityAlerts(communityId: String, limit: Int = 50) async throws {
        let path = "/v1/communities/\(communityId)/alerts?limit=\(limit)"
        let endpoint = try endpointURL(path: path)
        let request = try buildRequest(url: endpoint, method: "GET")

        let data = try await perform(request)
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        communityAlerts = try decoder.decode([AlertEvent].self, from: data)
    }

    /// Broadcast an alert to a community
    func broadcastAlert(
        communityId: String,
        alert: AlertEvent
    ) async throws {
        let path = "/v1/communities/\(communityId)/alerts"
        let endpoint = try endpointURL(path: path)
        var request = try buildRequest(url: endpoint, method: "POST")

        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        request.httpBody = try encoder.encode(alert)

        _ = try await perform(request)
    }

    /// Invite a neighbor to the community
    func inviteNeighbor(
        communityId: String,
        neighborNodeId: String
    ) async throws {
        let path = "/v1/communities/\(communityId)/invite"
        let endpoint = try endpointURL(path: path)
        var request = try buildRequest(url: endpoint, method: "POST")

        struct InviteRequest: Codable {
            let neighborNodeId: String

            enum CodingKeys: String, CodingKey {
                case neighborNodeId = "neighbor_node_id"
            }
        }

        let body = InviteRequest(neighborNodeId: neighborNodeId)
        request.httpBody = try JSONEncoder().encode(body)

        _ = try await perform(request)
    }

    // MARK: - Convenience Methods

    func setActiveCommunity(_ community: Community?) {
        activeCommunity = community
    }

    func community(withId id: String) -> Community? {
        communities.first { $0.id == id }
    }

    func isMember(of communityId: String) -> Bool {
        communities.contains { $0.id == communityId }
    }
}

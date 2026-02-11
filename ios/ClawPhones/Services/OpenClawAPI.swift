//
//  OpenClawAPI.swift
//  ClawPhones
//
//  HTTP client for api.openclaw.ai
//

import Foundation

final class OpenClawAPI {
    static let shared = OpenClawAPI()
    private init() {}

    private var baseURLString: String {
        DeviceConfig.shared.baseURL.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
    }
    
    var baseURL: String {
        baseURLString
    }

    // MARK: - Auth + User Models (backend sync in progress)

    struct AuthRegisterResponse: Codable, Hashable {
        let userId: String
        let token: String
        let tier: String

        enum CodingKeys: String, CodingKey {
            case userId = "user_id"
            case token
            case tier
        }
    }

    struct AuthLoginResponse: Codable, Hashable {
        let userId: String
        let token: String
        let tier: String
        let name: String?
        let aiConfig: AIConfigResponse?

        enum CodingKeys: String, CodingKey {
            case userId = "user_id"
            case token
            case tier
            case name
            case aiConfig = "ai_config"
        }
    }

    struct UserProfileResponse: Codable, Hashable {
        let userId: String
        let email: String
        let name: String?
        let tier: String
        let language: String?

        enum CodingKeys: String, CodingKey {
            case userId = "user_id"
            case email
            case name
            case tier
            case language
        }
    }

    struct PlanResponse: Codable, Hashable {
        struct Limits: Codable, Hashable {
            let messagesPerDay: Int?

            enum CodingKeys: String, CodingKey {
                case messagesPerDay = "messages_per_day"
            }
        }

        struct Usage: Codable, Hashable {
            let messagesToday: Int?

            enum CodingKeys: String, CodingKey {
                case messagesToday = "messages_today"
            }
        }

        let tier: String
        let limits: Limits?
        let usage: Usage?

        enum CodingKeys: String, CodingKey {
            case tier
            case limits
            case usage
        }
    }

    struct AIConfigResponse: Codable, Hashable {
        let persona: String
        let customPrompt: String?
        let temperature: Double?
        let availablePersonas: [String]?

        enum CodingKeys: String, CodingKey {
            case persona
            case customPrompt = "custom_prompt"
            case temperature
            case availablePersonas = "available_personas"
        }
    }

    // MARK: - Endpoints

    // MARK: Auth

    func register(email: String, password: String, name: String? = nil) async throws -> AuthRegisterResponse {
        let url = URL(string: "\(baseURLString)/v1/auth/register")!
        var request = request(url: url, method: "POST")
        request.addValue("application/json", forHTTPHeaderField: "Accept")
        request.addValue("application/json", forHTTPHeaderField: "Content-Type")

        let body = AuthRegisterRequestBody(email: email, password: password, name: name)
        request.httpBody = try JSONEncoder().encode(body)

        let (data, response) = try await URLSession.shared.data(for: request)
        try validate(response: response, data: data)

        return try decode(AuthRegisterResponse.self, from: data)
    }

    func login(email: String, password: String) async throws -> AuthLoginResponse {
        let url = URL(string: "\(baseURLString)/v1/auth/login")!
        var request = request(url: url, method: "POST")
        request.addValue("application/json", forHTTPHeaderField: "Accept")
        request.addValue("application/json", forHTTPHeaderField: "Content-Type")

        let body = AuthLoginRequestBody(email: email, password: password)
        request.httpBody = try JSONEncoder().encode(body)

        let (data, response) = try await URLSession.shared.data(for: request)
        try validate(response: response, data: data)

        return try decode(AuthLoginResponse.self, from: data)
    }

    func loginWithApple(identityToken: String) async throws -> AuthLoginResponse {
        // TODO: backend endpoint not finalized; keeping a stub so UI can ship.
        throw ClawPhonesError.apiError("Sign in with Apple is coming soon.")
    }

    // MARK: User

    func getUserProfile() async throws -> UserProfileResponse {
        let url = URL(string: "\(baseURLString)/v1/user/profile")!
        var request = try authorizedRequest(url: url, method: "GET")
        request.addValue("application/json", forHTTPHeaderField: "Accept")

        let (data, response) = try await URLSession.shared.data(for: request)
        try validate(response: response, data: data)

        return try decode(UserProfileResponse.self, from: data)
    }

    func updateUserProfile(name: String?, language: String?) async throws -> UserProfileResponse {
        let url = URL(string: "\(baseURLString)/v1/user/profile")!
        var request = try authorizedRequest(url: url, method: "PUT")
        request.addValue("application/json", forHTTPHeaderField: "Accept")
        request.addValue("application/json", forHTTPHeaderField: "Content-Type")

        let body = UpdateUserProfileRequestBody(name: name, language: language)
        request.httpBody = try JSONEncoder().encode(body)

        let (data, response) = try await URLSession.shared.data(for: request)
        try validate(response: response, data: data)

        return try decode(UserProfileResponse.self, from: data)
    }

    func updatePassword(oldPassword: String, newPassword: String) async throws -> Bool {
        let url = URL(string: "\(baseURLString)/v1/user/password")!
        var request = try authorizedRequest(url: url, method: "PUT")
        request.addValue("application/json", forHTTPHeaderField: "Accept")
        request.addValue("application/json", forHTTPHeaderField: "Content-Type")

        let body = UpdatePasswordRequestBody(oldPassword: oldPassword, newPassword: newPassword)
        request.httpBody = try JSONEncoder().encode(body)

        let (data, response) = try await URLSession.shared.data(for: request)
        try validate(response: response, data: data)
        return true
    }

    func getPlan() async throws -> PlanResponse {
        let url = URL(string: "\(baseURLString)/v1/user/plan")!
        var request = try authorizedRequest(url: url, method: "GET")
        request.addValue("application/json", forHTTPHeaderField: "Accept")

        let (data, response) = try await URLSession.shared.data(for: request)
        try validate(response: response, data: data)

        return try decode(PlanResponse.self, from: data)
    }

    func getAIConfig() async throws -> AIConfigResponse {
        let url = URL(string: "\(baseURLString)/v1/user/ai-config")!
        var request = try authorizedRequest(url: url, method: "GET")
        request.addValue("application/json", forHTTPHeaderField: "Accept")

        let (data, response) = try await URLSession.shared.data(for: request)
        try validate(response: response, data: data)

        return try decode(AIConfigResponse.self, from: data)
    }

    func updateAIConfig(persona: String?, customPrompt: String?, temperature: Double?) async throws -> AIConfigResponse {
        let url = URL(string: "\(baseURLString)/v1/user/ai-config")!
        var request = try authorizedRequest(url: url, method: "PUT")
        request.addValue("application/json", forHTTPHeaderField: "Accept")
        request.addValue("application/json", forHTTPHeaderField: "Content-Type")

        let body = UpdateAIConfigRequestBody(persona: persona, customPrompt: customPrompt, temperature: temperature)
        request.httpBody = try JSONEncoder().encode(body)

        let (data, response) = try await URLSession.shared.data(for: request)
        try validate(response: response, data: data)

        return try decode(AIConfigResponse.self, from: data)
    }

    // MARK: Conversations

    func createConversation(systemPrompt: String? = nil) async throws -> Conversation {
        let url = URL(string: "\(baseURLString)/v1/conversations")!
        var request = try authorizedRequest(url: url, method: "POST")
        request.addValue("application/json", forHTTPHeaderField: "Accept")
        request.addValue("application/json", forHTTPHeaderField: "Content-Type")

        var body: [String: String] = [:]
        if let prompt = systemPrompt, !prompt.isEmpty {
            body["system_prompt"] = prompt
        }
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, response) = try await URLSession.shared.data(for: request)
        try validate(response: response, data: data)

        return try decode(Conversation.self, from: data)
    }

    func listConversations(limit: Int = 20, offset: Int = 0) async throws -> [ConversationSummary] {
        var components = URLComponents(string: "\(baseURLString)/v1/conversations")!
        components.queryItems = [
            URLQueryItem(name: "limit", value: String(limit)),
            URLQueryItem(name: "offset", value: String(offset))
        ]
        let url = components.url!

        var request = try authorizedRequest(url: url, method: "GET")
        request.addValue("application/json", forHTTPHeaderField: "Accept")

        let (data, response) = try await URLSession.shared.data(for: request)
        try validate(response: response, data: data)

        let payload = try decode(ConversationListResponse.self, from: data)
        return payload.conversations
    }

    func getConversation(id: String) async throws -> ConversationDetail {
        let url = URL(string: "\(baseURLString)/v1/conversations/\(id)")!
        var request = try authorizedRequest(url: url, method: "GET")
        request.addValue("application/json", forHTTPHeaderField: "Accept")

        let (data, response) = try await URLSession.shared.data(for: request)
        try validate(response: response, data: data)

        return try decode(ConversationDetail.self, from: data)
    }

    func chat(conversationId: String, message: String) async throws -> ChatMessageResponse {
        let url = URL(string: "\(baseURLString)/v1/conversations/\(conversationId)/chat")!
        var request = try authorizedRequest(url: url, method: "POST")
        request.addValue("application/json", forHTTPHeaderField: "Content-Type")
        request.addValue("application/json", forHTTPHeaderField: "Accept")

        let body = ChatRequestBody(message: message)
        request.httpBody = try JSONEncoder().encode(body)

        let (data, response) = try await URLSession.shared.data(for: request)
        try validate(response: response, data: data)

        return try decode(ChatMessageResponse.self, from: data)
    }

    func chatStream(conversationId: String, message: String) -> AsyncThrowingStream<StreamChunk, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    let url = URL(string: "\(baseURLString)/v1/conversations/\(conversationId)/chat/stream")!
                    var request = try authorizedRequest(url: url, method: "POST")
                    request.timeoutInterval = 300
                    request.addValue("application/json", forHTTPHeaderField: "Content-Type")
                    request.addValue("text/event-stream", forHTTPHeaderField: "Accept")

                    let body = ChatRequestBody(message: message)
                    request.httpBody = try JSONEncoder().encode(body)

                    let (bytes, response) = try await URLSession.shared.bytes(for: request)

                    guard let httpResponse = response as? HTTPURLResponse else {
                        throw ClawPhonesError.networkError(URLError(.badServerResponse))
                    }
	                    guard (200...299).contains(httpResponse.statusCode) else {
	                        var bodyData = Data()
	                        for try await chunk in bytes {
	                            bodyData.append(contentsOf: [chunk])
	                        }
	                        try validate(response: response, data: bodyData)
	                        continuation.finish()
	                        return
	                    }

                    let decoder = JSONDecoder()

                    for try await rawLine in bytes.lines {
                        if Task.isCancelled {
                            continuation.finish()
                            return
                        }

                        let line = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)

                        guard line.hasPrefix("data:") else { continue }

                        var dataPart = line.dropFirst("data:".count)
                        if dataPart.first == " " {
                            dataPart = dataPart.dropFirst()
                        }
                        let dataString = String(dataPart)
                        guard !dataString.isEmpty else { continue }

                        if dataString == "[DONE]" {
                            continuation.finish()
                            return
                        }

                        let payloadData = Data(dataString.utf8)
                        let payload = try decoder.decode(SSEChatChunk.self, from: payloadData)
                        let chunk = StreamChunk(
                            delta: payload.delta ?? "",
                            done: payload.done ?? false,
                            messageId: payload.messageId,
                            fullContent: payload.content
                        )
                        continuation.yield(chunk)

                        if chunk.done {
                            continuation.finish()
                            return
                        }
                    }

                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }

            continuation.onTermination = { _ in
                task.cancel()
            }
        }
    }

    func deleteConversation(id: String) async throws -> Bool {
        let url = URL(string: "\(baseURLString)/v1/conversations/\(id)")!
        var request = try authorizedRequest(url: url, method: "DELETE")
        request.addValue("application/json", forHTTPHeaderField: "Accept")

        let (data, response) = try await URLSession.shared.data(for: request)
        try validate(response: response, data: data)

        let payload = try decode(DeleteConversationResponse.self, from: data)
        return payload.deleted
    }

    // MARK: - Internals

    private struct ChatRequestBody: Encodable {
        let message: String
    }

    private struct SSEChatChunk: Decodable {
        let delta: String?
        let done: Bool?
        let messageId: String?
        let content: String?

        enum CodingKeys: String, CodingKey {
            case delta
            case done
            case messageId = "message_id"
            case content
        }
    }

    private struct AuthRegisterRequestBody: Encodable {
        let email: String
        let password: String
        let name: String?
    }

    private struct AuthLoginRequestBody: Encodable {
        let email: String
        let password: String
    }

    private struct UpdateUserProfileRequestBody: Encodable {
        let name: String?
        let language: String?
    }

    private struct UpdatePasswordRequestBody: Encodable {
        let oldPassword: String
        let newPassword: String

        enum CodingKeys: String, CodingKey {
            case oldPassword = "old_password"
            case newPassword = "new_password"
        }
    }

    private struct UpdateAIConfigRequestBody: Encodable {
        let persona: String?
        let customPrompt: String?
        let temperature: Double?

        enum CodingKeys: String, CodingKey {
            case persona
            case customPrompt = "custom_prompt"
            case temperature
        }
    }

    private func request(url: URL, method: String) -> URLRequest {
        var request = URLRequest(url: url)
        request.httpMethod = method
        return request
    }

    private func authorizedRequest(url: URL, method: String) throws -> URLRequest {
        guard let token = DeviceConfig.shared.deviceToken, !token.isEmpty else {
            throw ClawPhonesError.noDeviceToken
        }

        var request = URLRequest(url: url)
        request.httpMethod = method
        request.addValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        return request
    }

    private func validate(response: URLResponse, data: Data) throws {
        guard let httpResponse = response as? HTTPURLResponse else {
            throw ClawPhonesError.networkError(URLError(.badServerResponse))
        }

        guard (200...299).contains(httpResponse.statusCode) else {
            if httpResponse.statusCode >= 500 {
                CrashReporter.shared.reportNonFatal(
                    error: NSError(
                        domain: "API",
                        code: httpResponse.statusCode,
                        userInfo: [NSLocalizedDescriptionKey: "HTTP \(httpResponse.statusCode)"]
                    ),
                    action: "api_call"
                )
            }

            if httpResponse.statusCode == 401 {
                // Auto-clear invalid token
                DeviceConfig.shared.clearTokens()
                // Post notification so UI can react
                NotificationCenter.default.post(name: Notification.Name("ClawPhonesAuthExpired"), object: nil)
                throw ClawPhonesError.unauthorized
            }

            let message = String(data: data, encoding: .utf8) ?? "Unknown error"
            throw ClawPhonesError.apiError(message)
        }
    }

    private func decode<T: Decodable>(_ type: T.Type, from data: Data) throws -> T {
        do {
            return try JSONDecoder().decode(T.self, from: data)
        } catch {
            print("[ClawPhones] Decode error for \(T.self): \(error)")
            print("[ClawPhones] Raw data: \(String(data: data, encoding: .utf8) ?? "non-utf8")")
            throw ClawPhonesError.decodingError
        }
    }
}

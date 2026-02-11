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
    private let tokenTTLSeconds = 30 * 24 * 60 * 60
    private let tokenRefreshWindowSeconds = 7 * 24 * 60 * 60

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
        let expiresAt: Int?

        enum CodingKeys: String, CodingKey {
            case userId = "user_id"
            case token
            case tier
            case expiresAt = "expires_at"
        }
    }

    struct AuthLoginResponse: Codable, Hashable {
        let userId: String
        let token: String
        let tier: String
        let name: String?
        let aiConfig: AIConfigResponse?
        let expiresAt: Int?

        enum CodingKeys: String, CodingKey {
            case userId = "user_id"
            case token
            case tier
            case name
            case aiConfig = "ai_config"
            case expiresAt = "expires_at"
        }
    }

    struct AuthRefreshResponse: Codable, Hashable {
        let token: String
        let tier: String?
        let expiresAt: Int?

        enum CodingKeys: String, CodingKey {
            case token
            case tier
            case expiresAt = "expires_at"
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

    struct UserDataExportResponse: Codable, Hashable {
        let exportId: String
        let downloadURL: String
        let expiresAt: Int

        enum CodingKeys: String, CodingKey {
            case exportId = "export_id"
            case downloadURL = "download_url"
            case expiresAt = "expires_at"
        }
    }

    struct UploadResponse: Codable, Hashable {
        let fileId: String
        let url: String
        let mimeType: String
        let size: Int

        enum CodingKeys: String, CodingKey {
            case fileId = "file_id"
            case url
            case mimeType = "mime_type"
            case size
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

    func refresh() async throws -> AuthRefreshResponse {
        guard let token = DeviceConfig.shared.deviceToken, !token.isEmpty else {
            throw ClawPhonesError.noDeviceToken
        }

        let url = URL(string: "\(baseURLString)/v1/auth/refresh")!
        var request = request(url: url, method: "POST")
        request.addValue("application/json", forHTTPHeaderField: "Accept")
        request.addValue("application/json", forHTTPHeaderField: "Content-Type")
        request.addValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.httpBody = Data("{}".utf8)

        let (data, response) = try await URLSession.shared.data(for: request)
        try validate(response: response, data: data)

        return try decode(AuthRefreshResponse.self, from: data)
    }

    func loginWithApple(identityToken: String, userIdentifier: String, email: String?, fullName: String?) async throws -> AuthLoginResponse {
        let url = URL(string: "\(baseURLString)/v1/auth/apple")!
        var request = request(url: url, method: "POST")
        request.addValue("application/json", forHTTPHeaderField: "Accept")
        request.addValue("application/json", forHTTPHeaderField: "Content-Type")

        let body = AuthAppleRequestBody(
            identityToken: identityToken,
            userIdentifier: userIdentifier,
            email: email,
            fullName: fullName
        )
        request.httpBody = try JSONEncoder().encode(body)

        let (data, response) = try await URLSession.shared.data(for: request)
        try validate(response: response, data: data)

        return try decode(AuthLoginResponse.self, from: data)
    }

    // MARK: User

    func getUserProfile() async throws -> UserProfileResponse {
        let url = URL(string: "\(baseURLString)/v1/user/profile")!
        var request = try await authorizedRequest(url: url, method: "GET")
        request.addValue("application/json", forHTTPHeaderField: "Accept")

        let (data, response) = try await URLSession.shared.data(for: request)
        try validate(response: response, data: data)

        return try decode(UserProfileResponse.self, from: data)
    }

    func updateUserProfile(name: String?, language: String?) async throws -> UserProfileResponse {
        let url = URL(string: "\(baseURLString)/v1/user/profile")!
        var request = try await authorizedRequest(url: url, method: "PUT")
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
        var request = try await authorizedRequest(url: url, method: "PUT")
        request.addValue("application/json", forHTTPHeaderField: "Accept")
        request.addValue("application/json", forHTTPHeaderField: "Content-Type")

        let body = UpdatePasswordRequestBody(oldPassword: oldPassword, newPassword: newPassword)
        request.httpBody = try JSONEncoder().encode(body)

        let (data, response) = try await URLSession.shared.data(for: request)
        try validate(response: response, data: data)
        return true
    }

    func deleteAccount(confirm: Bool = true) async throws {
        let url = URL(string: "\(baseURLString)/v1/user/account")!
        var request = try await authorizedRequest(url: url, method: "DELETE")
        request.addValue("application/json", forHTTPHeaderField: "Accept")
        request.addValue("application/json", forHTTPHeaderField: "Content-Type")

        let body = DeleteAccountRequestBody(confirm: confirm)
        request.httpBody = try JSONEncoder().encode(body)

        let (data, response) = try await URLSession.shared.data(for: request)
        try validate(response: response, data: data)
    }

    func getPlan() async throws -> PlanResponse {
        let url = URL(string: "\(baseURLString)/v1/user/plan")!
        var request = try await authorizedRequest(url: url, method: "GET")
        request.addValue("application/json", forHTTPHeaderField: "Accept")

        let (data, response) = try await URLSession.shared.data(for: request)
        try validate(response: response, data: data)

        return try decode(PlanResponse.self, from: data)
    }

    func getAIConfig() async throws -> AIConfigResponse {
        let url = URL(string: "\(baseURLString)/v1/user/ai-config")!
        var request = try await authorizedRequest(url: url, method: "GET")
        request.addValue("application/json", forHTTPHeaderField: "Accept")

        let (data, response) = try await URLSession.shared.data(for: request)
        try validate(response: response, data: data)

        return try decode(AIConfigResponse.self, from: data)
    }

    func updateAIConfig(persona: String?, customPrompt: String?, temperature: Double?) async throws -> AIConfigResponse {
        let url = URL(string: "\(baseURLString)/v1/user/ai-config")!
        var request = try await authorizedRequest(url: url, method: "PUT")
        request.addValue("application/json", forHTTPHeaderField: "Accept")
        request.addValue("application/json", forHTTPHeaderField: "Content-Type")

        let body = UpdateAIConfigRequestBody(persona: persona, customPrompt: customPrompt, temperature: temperature)
        request.httpBody = try JSONEncoder().encode(body)

        let (data, response) = try await URLSession.shared.data(for: request)
        try validate(response: response, data: data)

        return try decode(AIConfigResponse.self, from: data)
    }

    func createUserDataExport() async throws -> UserDataExportResponse {
        let url = URL(string: "\(baseURLString)/v1/user/export")!
        var request = try await authorizedRequest(url: url, method: "POST")
        request.addValue("application/json", forHTTPHeaderField: "Accept")
        request.addValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = Data("{}".utf8)

        let (data, response) = try await URLSession.shared.data(for: request)
        try validate(response: response, data: data)

        return try decode(UserDataExportResponse.self, from: data)
    }

    func downloadUserDataExport(from downloadURLString: String, exportId: String? = nil) async throws -> URL {
        guard let downloadURL = URL(string: downloadURLString) else {
            throw ClawPhonesError.apiError("Invalid export download URL")
        }

        let (data, response) = try await URLSession.shared.data(from: downloadURL)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw ClawPhonesError.networkError(URLError(.badServerResponse))
        }
        guard (200...299).contains(httpResponse.statusCode) else {
            let message = String(data: data, encoding: .utf8) ?? "HTTP \(httpResponse.statusCode)"
            throw ClawPhonesError.apiError(message)
        }

        let base = (exportId ?? UUID().uuidString)
            .replacingOccurrences(of: "[^A-Za-z0-9_-]", with: "", options: .regularExpression)
        let safeBase = base.isEmpty ? UUID().uuidString : base
        let fileName = "clawphones_export_\(safeBase)_\(Int(Date().timeIntervalSince1970)).json"
        let fileURL = FileManager.default.temporaryDirectory.appendingPathComponent(fileName)

        do {
            try data.write(to: fileURL, options: .atomic)
        } catch {
            throw ClawPhonesError.networkError(error)
        }
        return fileURL
    }

    func exportUserDataToTemporaryFile() async throws -> URL {
        let payload = try await createUserDataExport()
        return try await downloadUserDataExport(from: payload.downloadURL, exportId: payload.exportId)
    }

    // MARK: World Coverage

    /// GET /v1/world/cells?hours=&res=
    /// Returns raw payload since relay schema may evolve.
    func getWorldCells(hours: Int = 24, res: Int = 9) async throws -> Data {
        var components = URLComponents(string: "\(baseURLString)/v1/world/cells")!
        components.queryItems = [
            URLQueryItem(name: "hours", value: String(max(1, hours))),
            URLQueryItem(name: "res", value: String(max(0, res)))
        ]
        let url = components.url!

        var request = try await authorizedRequest(url: url, method: "GET")
        request.addValue("application/json", forHTTPHeaderField: "Accept")

        let (data, response) = try await URLSession.shared.data(for: request)
        try validate(response: response, data: data)
        return data
    }

    // MARK: Conversations

    func createConversation(systemPrompt: String? = nil) async throws -> Conversation {
        let url = URL(string: "\(baseURLString)/v1/conversations")!
        var request = try await authorizedRequest(url: url, method: "POST")
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

        var request = try await authorizedRequest(url: url, method: "GET")
        request.addValue("application/json", forHTTPHeaderField: "Accept")

        let (data, response) = try await URLSession.shared.data(for: request)
        try validate(response: response, data: data)

        let payload = try decode(ConversationListResponse.self, from: data)
        return payload.conversations
    }

    func getConversation(id: String) async throws -> ConversationDetail {
        let url = URL(string: "\(baseURLString)/v1/conversations/\(id)")!
        var request = try await authorizedRequest(url: url, method: "GET")
        request.addValue("application/json", forHTTPHeaderField: "Accept")

        let (data, response) = try await URLSession.shared.data(for: request)
        try validate(response: response, data: data)

        return try decode(ConversationDetail.self, from: data)
    }

    func uploadFile(conversationId: String, fileData: Data, filename: String, mimeType: String) async throws -> UploadResponse {
        var components = URLComponents(string: "\(baseURLString)/v1/upload")!
        components.queryItems = [URLQueryItem(name: "conversation_id", value: conversationId)]
        let url = components.url!
        var request = try await authorizedRequest(url: url, method: "POST")
        request.addValue("application/json", forHTTPHeaderField: "Accept")

        let boundary = "Boundary-\(UUID().uuidString)"
        request.addValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        request.httpBody = multipartBody(
            boundary: boundary,
            fileData: fileData,
            filename: filename,
            mimeType: mimeType
        )

        let (data, response) = try await URLSession.shared.data(for: request)
        try validate(response: response, data: data)
        return try decode(UploadResponse.self, from: data)
    }

    func chat(conversationId: String, message: String, fileIds: [String]? = nil) async throws -> ChatMessageResponse {
        let url = URL(string: "\(baseURLString)/v1/conversations/\(conversationId)/chat")!
        var request = try await authorizedRequest(url: url, method: "POST")
        request.addValue("application/json", forHTTPHeaderField: "Content-Type")
        request.addValue("application/json", forHTTPHeaderField: "Accept")

        let body = ChatRequestBody(message: message, fileIds: fileIds)
        request.httpBody = try JSONEncoder().encode(body)

        let (data, response) = try await URLSession.shared.data(for: request)
        try validate(response: response, data: data)

        return try decode(ChatMessageResponse.self, from: data)
    }

    func chatStream(conversationId: String, message: String, fileIds: [String]? = nil) -> AsyncThrowingStream<StreamChunk, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    let url = URL(string: "\(baseURLString)/v1/conversations/\(conversationId)/chat/stream")!
                    var request = try await authorizedRequest(url: url, method: "POST")
                    request.timeoutInterval = 300
                    request.addValue("application/json", forHTTPHeaderField: "Content-Type")
                    request.addValue("text/event-stream", forHTTPHeaderField: "Accept")

                    let body = ChatRequestBody(message: message, fileIds: fileIds)
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
        var request = try await authorizedRequest(url: url, method: "DELETE")
        request.addValue("application/json", forHTTPHeaderField: "Accept")

        let (data, response) = try await URLSession.shared.data(for: request)
        try validate(response: response, data: data)

        let payload = try decode(DeleteConversationResponse.self, from: data)
        return payload.deleted
    }

    // MARK: - Internals

    private struct ChatRequestBody: Encodable {
        let message: String
        let fileIds: [String]?

        enum CodingKeys: String, CodingKey {
            case message
            case fileIds = "file_ids"
        }
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

    private struct AuthAppleRequestBody: Encodable {
        let identityToken: String
        let userIdentifier: String
        let email: String?
        let fullName: String?

        enum CodingKeys: String, CodingKey {
            case identityToken = "identity_token"
            case userIdentifier = "user_identifier"
            case email
            case fullName = "full_name"
        }
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

    private struct DeleteAccountRequestBody: Encodable {
        let confirm: Bool
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

    private func multipartBody(boundary: String, fileData: Data, filename: String, mimeType: String) -> Data {
        var body = Data()
        let safeFilename = filename.isEmpty ? "upload.bin" : filename
        let safeMime = mimeType.isEmpty ? "application/octet-stream" : mimeType

        body.append(Data("--\(boundary)\r\n".utf8))
        body.append(Data("Content-Disposition: form-data; name=\"file\"; filename=\"\(safeFilename)\"\r\n".utf8))
        body.append(Data("Content-Type: \(safeMime)\r\n\r\n".utf8))
        body.append(fileData)
        body.append(Data("\r\n--\(boundary)--\r\n".utf8))
        return body
    }

    private func request(url: URL, method: String) -> URLRequest {
        var request = URLRequest(url: url)
        request.httpMethod = method
        return request
    }

    private func authorizedRequest(url: URL, method: String) async throws -> URLRequest {
        let token = try await validTokenForAuthorizedRequest()
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.addValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        return request
    }

    private func validTokenForAuthorizedRequest() async throws -> String {
        guard let token = DeviceConfig.shared.deviceToken, !token.isEmpty else {
            throw ClawPhonesError.noDeviceToken
        }

        let now = Int(Date().timeIntervalSince1970)
        var expiresAt = DeviceConfig.shared.tokenExpiresAt ?? 0
        if expiresAt <= 0 {
            expiresAt = now + tokenTTLSeconds
            DeviceConfig.shared.tokenExpiresAt = expiresAt
        }

        if now >= expiresAt {
            expireAuthSession()
            throw ClawPhonesError.unauthorized
        }

        let remaining = expiresAt - now
        if remaining < tokenRefreshWindowSeconds {
            do {
                let refreshed = try await refresh()
                let refreshedToken = refreshed.token.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !refreshedToken.isEmpty else {
                    throw ClawPhonesError.unauthorized
                }
                DeviceConfig.shared.saveUserToken(
                    refreshedToken,
                    expiresAt: normalizeExpiry(refreshed.expiresAt)
                )
                return refreshedToken
            } catch let err as ClawPhonesError {
                if case .unauthorized = err {
                    throw err
                }
                // Keep current token on refresh failure to avoid blocking user requests.
            } catch {
                // Keep current token on refresh failure to avoid blocking user requests.
            }
        }

        return token
    }

    private func normalizeExpiry(_ raw: Int?) -> Int {
        if let raw, raw > 0 {
            return raw
        }
        return Int(Date().timeIntervalSince1970) + tokenTTLSeconds
    }

    private func expireAuthSession() {
        DeviceConfig.shared.clearTokens()
        NotificationCenter.default.post(name: Notification.Name("ClawPhonesAuthExpired"), object: nil)
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
                expireAuthSession()
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

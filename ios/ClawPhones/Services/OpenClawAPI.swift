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

    // MARK: - Endpoints

    func createConversation() async throws -> Conversation {
        let url = URL(string: "\(baseURLString)/v1/conversations")!
        var request = try authorizedRequest(url: url, method: "POST")
        request.addValue("application/json", forHTTPHeaderField: "Accept")

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
            if httpResponse.statusCode == 401 {
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
            throw ClawPhonesError.decodingError
        }
    }
}

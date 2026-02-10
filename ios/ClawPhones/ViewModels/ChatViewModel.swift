//
//  ChatViewModel.swift
//  ClawPhones
//

import SwiftUI

@MainActor
final class ChatViewModel: ObservableObject {
    @Published var messages: [Message] = []
    @Published var isLoading: Bool = false
    @Published var errorMessage: String?
    @Published var conversationTitle: String?

    private(set) var conversationId: String?

    init(conversationId: String? = nil) {
        self.conversationId = conversationId
    }

    func startNewConversation() async {
        errorMessage = nil
        isLoading = true
        defer { isLoading = false }

        do {
            let conversation = try await OpenClawAPI.shared.createConversation()
            conversationId = conversation.id
            conversationTitle = conversation.title ?? "New Chat"
            messages = []
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func loadConversation(id: String) async {
        errorMessage = nil
        isLoading = true
        defer { isLoading = false }

        do {
            let detail = try await OpenClawAPI.shared.getConversation(id: id)
            conversationId = detail.id
            conversationTitle = detail.title ?? "Untitled"
            messages = detail.messages
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func sendMessage(text: String) async {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }

        errorMessage = nil

        if conversationId == nil {
            await startNewConversation()
        }

        guard let cid = conversationId else {
            return
        }

        let now = Int(Date().timeIntervalSince1970)
        let userMessage = Message(id: UUID().uuidString, role: .user, content: trimmed, createdAt: now)
        messages.append(userMessage)

        isLoading = true
        defer { isLoading = false }

        do {
            let response = try await OpenClawAPI.shared.chat(conversationId: cid, message: trimmed)
            let reply = Message(id: response.messageId, role: response.role, content: response.content, createdAt: response.createdAt)
            messages.append(reply)
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

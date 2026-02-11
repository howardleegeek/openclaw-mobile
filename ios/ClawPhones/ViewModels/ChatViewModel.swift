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

    static let defaultSystemPrompt = """
    你是 ClawPhones AI 助手，由 Oyster Labs 开发。你聪明、友好、高效。
    - 用用户的语言回复（中文问中文答，英文问英文答）
    - 回复简洁有用，避免冗长
    - 允许使用 Markdown（加粗、斜体、代码、链接、列表）让内容更清晰
    """

    init(conversationId: String? = nil) {
        self.conversationId = conversationId
    }

    func startNewConversation() async {
        errorMessage = nil
        isLoading = true
        defer { isLoading = false }

        do {
            let conversation = try await OpenClawAPI.shared.createConversation(
                systemPrompt: Self.defaultSystemPrompt
            )
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

        guard !isLoading else { return }
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

        let placeholderId = UUID().uuidString
        let placeholder = Message(id: placeholderId, role: .assistant, content: "", createdAt: now)
        messages.append(placeholder)

        isLoading = true
        defer { isLoading = false }

        await streamOrFallbackChat(conversationId: cid, prompt: trimmed, placeholderId: placeholderId)
    }

    func regenerateAssistantMessage(messageId: String) async {
        guard !isLoading else { return }
        guard let cid = conversationId else { return }
        guard let assistantIndex = messages.firstIndex(where: { $0.id == messageId }) else { return }
        guard messages[assistantIndex].role == .assistant else { return }
        guard let userIndex = messages[..<assistantIndex].lastIndex(where: { $0.role == .user }) else { return }

        errorMessage = nil

        let prompt = messages[userIndex].content
        let now = Int(Date().timeIntervalSince1970)
        let placeholderId = UUID().uuidString
        messages[assistantIndex] = Message(id: placeholderId, role: .assistant, content: "", createdAt: now)

        isLoading = true
        defer { isLoading = false }

        await streamOrFallbackChat(conversationId: cid, prompt: prompt, placeholderId: placeholderId)
    }

    func deleteMessage(messageId: String) {
        messages.removeAll { $0.id == messageId }
    }

    // MARK: - Streaming Helpers

    private func streamOrFallbackChat(conversationId: String, prompt: String, placeholderId: String) async {
        var content = ""
        var didReceiveAnyChunk = false

        do {
            let stream = OpenClawAPI.shared.chatStream(conversationId: conversationId, message: prompt)
            for try await chunk in stream {
                didReceiveAnyChunk = true

                if chunk.done {
                    let finalContent = chunk.fullContent ?? content
                    let finalId = chunk.messageId ?? placeholderId
                    let now = Int(Date().timeIntervalSince1970)
                    let finalMessage = Message(id: finalId, role: .assistant, content: finalContent, createdAt: now)
                    replaceMessage(id: placeholderId, with: finalMessage)
                    return
                }

                if !chunk.delta.isEmpty {
                    content += chunk.delta
                    updateMessageContent(id: placeholderId, content: content)
                }
            }

            if didReceiveAnyChunk {
                updateMessageContent(id: placeholderId, content: content)
                return
            }
        } catch {
            CrashReporter.shared.reportNonFatal(error: error, action: "streaming_chat")
            if didReceiveAnyChunk {
                errorMessage = error.localizedDescription
                return
            }
        }

        // Fallback to non-streaming endpoint (e.g. backend doesn't support SSE yet).
        do {
            let response = try await OpenClawAPI.shared.chat(conversationId: conversationId, message: prompt)
            let reply = Message(
                id: response.messageId,
                role: response.role,
                content: response.content,
                createdAt: response.createdAt
            )
            replaceMessage(id: placeholderId, with: reply)
        } catch {
            errorMessage = error.localizedDescription
            deleteMessage(messageId: placeholderId)
        }
    }

    private func updateMessageContent(id: String, content: String) {
        guard let index = messages.firstIndex(where: { $0.id == id }) else { return }
        let current = messages[index]
        messages[index] = Message(id: current.id, role: current.role, content: content, createdAt: current.createdAt)
    }

    private func replaceMessage(id: String, with newMessage: Message) {
        guard let index = messages.firstIndex(where: { $0.id == id }) else { return }
        messages[index] = newMessage
    }
}

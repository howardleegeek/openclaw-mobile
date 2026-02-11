//
//  ConversationCache.swift
//  ClawPhones
//

import Foundation

actor ConversationCache {
    static let shared = ConversationCache()

    let maxConversations = 50
    let maxMessagesPerConversation = 100

    private let fileManager = FileManager.default
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    private init() {}

    private var cacheDirectory: URL {
        let base = fileManager.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let dir = base.appendingPathComponent("conversation_cache", isDirectory: true)
        if !fileManager.fileExists(atPath: dir.path) {
            try? fileManager.createDirectory(at: dir, withIntermediateDirectories: true)
        }
        return dir
    }

    private var conversationsFileURL: URL {
        cacheDirectory.appendingPathComponent("conversations.json")
    }

    private func messagesFileURL(conversationId: String) -> URL {
        let safe = safeFileName(conversationId)
        return cacheDirectory.appendingPathComponent("messages_\(safe).json")
    }

    func loadConversations() -> [ConversationSummary] {
        guard let data = try? Data(contentsOf: conversationsFileURL),
              let conversations = try? decoder.decode([ConversationSummary].self, from: data) else {
            return []
        }
        return normalizeConversations(conversations)
    }

    func saveConversations(_ conversations: [ConversationSummary]) {
        let normalized = normalizeConversations(conversations)
        guard let data = try? encoder.encode(normalized) else { return }
        try? data.write(to: conversationsFileURL, options: .atomic)
    }

    func upsertConversations(_ conversations: [ConversationSummary]) {
        var merged = loadConversations()
        let incoming = normalizeConversations(conversations)

        for item in incoming {
            if let index = merged.firstIndex(where: { $0.id == item.id }) {
                merged[index] = item
            } else {
                merged.append(item)
            }
        }
        saveConversations(merged)
    }

    func upsertConversation(
        id: String,
        title: String?,
        createdAt: Int? = nil,
        updatedAt: Int? = nil,
        lastMessage: String? = nil,
        messageCount: Int? = nil
    ) {
        guard !id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }

        var all = loadConversations()
        let now = Int(Date().timeIntervalSince1970)

        if let idx = all.firstIndex(where: { $0.id == id }) {
            let current = all[idx]
            all[idx] = ConversationSummary(
                id: id,
                title: title ?? current.title,
                lastMessage: lastMessage ?? current.lastMessage,
                createdAt: createdAt ?? current.createdAt,
                updatedAt: updatedAt ?? current.updatedAt ?? current.createdAt,
                messageCount: max(0, messageCount ?? current.messageCount)
            )
        } else {
            all.append(ConversationSummary(
                id: id,
                title: title,
                lastMessage: lastMessage,
                createdAt: createdAt ?? now,
                updatedAt: updatedAt ?? now,
                messageCount: max(0, messageCount ?? 0)
            ))
        }
        saveConversations(all)
    }

    func removeConversation(id: String) {
        let all = loadConversations().filter { $0.id != id }
        saveConversations(all)
        try? fileManager.removeItem(at: messagesFileURL(conversationId: id))
    }

    func loadMessages(conversationId: String) -> [Message] {
        let url = messagesFileURL(conversationId: conversationId)
        guard let data = try? Data(contentsOf: url),
              let messages = try? decoder.decode([Message].self, from: data) else {
            return []
        }
        return normalizeMessages(messages)
    }

    func replaceMessages(conversationId: String, messages: [Message]) {
        let normalized = normalizeMessages(messages)
        let url = messagesFileURL(conversationId: conversationId)
        guard let data = try? encoder.encode(normalized) else { return }
        try? data.write(to: url, options: .atomic)
    }

    func upsertMessages(conversationId: String, messages: [Message]) {
        var current = loadMessages(conversationId: conversationId)
        current.append(contentsOf: messages)
        replaceMessages(conversationId: conversationId, messages: current)
    }

    func clearAll() {
        let dir = cacheDirectory
        if fileManager.fileExists(atPath: dir.path) {
            try? fileManager.removeItem(at: dir)
        }
    }

    private func normalizeConversations(_ conversations: [ConversationSummary]) -> [ConversationSummary] {
        var bestById: [String: ConversationSummary] = [:]

        for item in conversations {
            if let existing = bestById[item.id] {
                let existingTs = existing.updatedAt ?? existing.createdAt
                let candidateTs = item.updatedAt ?? item.createdAt
                if candidateTs >= existingTs {
                    bestById[item.id] = item
                }
            } else {
                bestById[item.id] = item
            }
        }

        return bestById.values
            .sorted { lhs, rhs in
                let lt = lhs.updatedAt ?? lhs.createdAt
                let rt = rhs.updatedAt ?? rhs.createdAt
                return lt > rt
            }
            .prefix(maxConversations)
            .map { $0 }
    }

    private func normalizeMessages(_ messages: [Message]) -> [Message] {
        var latestByKey: [String: Message] = [:]

        for message in messages {
            latestByKey[messageKey(message)] = message
        }

        let sorted = latestByKey.values.sorted { lhs, rhs in
            if lhs.createdAt == rhs.createdAt {
                return lhs.id < rhs.id
            }
            return lhs.createdAt < rhs.createdAt
        }

        if sorted.count <= maxMessagesPerConversation {
            return sorted
        }
        return Array(sorted.suffix(maxMessagesPerConversation))
    }

    private func messageKey(_ message: Message) -> String {
        if !message.id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return "id:\(message.id)"
        }
        return "f:\(message.role.rawValue):\(message.createdAt):\(stableHash(message.content))"
    }

    private func safeFileName(_ input: String) -> String {
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_"))
        return input.unicodeScalars
            .map { allowed.contains($0) ? String($0) : "_" }
            .joined()
    }

    private func stableHash(_ value: String) -> String {
        var hash: UInt64 = 1469598103934665603
        let prime: UInt64 = 1099511628211
        for byte in value.utf8 {
            hash ^= UInt64(byte)
            hash = hash &* prime
        }
        return String(hash, radix: 16)
    }
}

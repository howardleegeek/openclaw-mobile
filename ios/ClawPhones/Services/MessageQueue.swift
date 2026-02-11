//
//  MessageQueue.swift
//  ClawPhones
//

import Foundation

final class MessageQueue {
    static let shared = MessageQueue()

    struct PendingMessage: Identifiable, Codable, Hashable {
        enum Status: String, Codable {
            case pending
            case sending
            case failed
        }

        let id: String
        let message: String
        var conversationId: String
        let createdAt: Int
        var status: Status
        var retryCount: Int
    }

    private let storageKey = "clawphones.pending_messages"
    private let storeQueue = DispatchQueue(label: "ai.clawphones.pending-message-queue")
    private var messages: [PendingMessage]

    private init() {
        messages = Self.loadFromStore(storageKey: storageKey)
    }

    func enqueue(message: String, conversationId: String?) -> PendingMessage {
        storeQueue.sync {
            let pending = PendingMessage(
                id: UUID().uuidString,
                message: message,
                conversationId: (conversationId ?? "").trimmingCharacters(in: .whitespacesAndNewlines),
                createdAt: Int(Date().timeIntervalSince1970),
                status: .pending,
                retryCount: 0
            )
            messages.append(pending)
            persistLocked()
            return pending
        }
    }

    func list(for conversationId: String?) -> [PendingMessage] {
        let normalized = (conversationId ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return storeQueue.sync {
            messages
                .filter { $0.conversationId == normalized }
                .sorted { $0.createdAt < $1.createdAt }
        }
    }

    func listWithoutConversation() -> [PendingMessage] {
        storeQueue.sync {
            messages
                .filter { $0.conversationId.isEmpty }
                .sorted { $0.createdAt < $1.createdAt }
        }
    }

    func nextPending(for conversationId: String?) -> PendingMessage? {
        let normalized = (conversationId ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return storeQueue.sync {
            let candidates = messages.filter { pending in
                guard pending.status == .pending || pending.status == .sending else { return false }
                if normalized.isEmpty {
                    return pending.conversationId.isEmpty
                }
                return pending.conversationId == normalized || pending.conversationId.isEmpty
            }
            return candidates.sorted { $0.createdAt < $1.createdAt }.first
        }
    }

    func updateConversationId(id: String, conversationId: String) {
        let normalized = conversationId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty else { return }
        storeQueue.sync {
            guard let index = messages.firstIndex(where: { $0.id == id }) else { return }
            messages[index].conversationId = normalized
            persistLocked()
        }
    }

    func assignConversationIdToEmpty(_ conversationId: String) {
        let normalized = conversationId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty else { return }
        storeQueue.sync {
            var changed = false
            for index in messages.indices where messages[index].conversationId.isEmpty {
                messages[index].conversationId = normalized
                changed = true
            }
            if changed {
                persistLocked()
            }
        }
    }

    func markSending(id: String) {
        updateStatus(id: id, status: .sending)
    }

    func markPending(id: String) {
        updateStatus(id: id, status: .pending)
    }

    func markFailed(id: String) {
        updateStatus(id: id, status: .failed)
    }

    func resetForManualRetry(id: String) {
        storeQueue.sync {
            guard let index = messages.firstIndex(where: { $0.id == id }) else { return }
            messages[index].status = .pending
            messages[index].retryCount = 0
            persistLocked()
        }
    }

    func incrementRetryCount(id: String) -> Int {
        storeQueue.sync {
            guard let index = messages.firstIndex(where: { $0.id == id }) else { return 0 }
            messages[index].retryCount += 1
            persistLocked()
            return messages[index].retryCount
        }
    }

    func remove(id: String) {
        storeQueue.sync {
            let before = messages.count
            messages.removeAll { $0.id == id }
            if messages.count != before {
                persistLocked()
            }
        }
    }

    func clearAll() {
        storeQueue.sync {
            messages.removeAll()
            UserDefaults.standard.removeObject(forKey: storageKey)
        }
    }

    private func updateStatus(id: String, status: PendingMessage.Status) {
        storeQueue.sync {
            guard let index = messages.firstIndex(where: { $0.id == id }) else { return }
            messages[index].status = status
            persistLocked()
        }
    }

    private func persistLocked() {
        do {
            let data = try JSONEncoder().encode(messages)
            UserDefaults.standard.set(data, forKey: storageKey)
        } catch {
            // Keep queue in memory even if persistence fails.
        }
    }

    private static func loadFromStore(storageKey: String) -> [PendingMessage] {
        guard let data = UserDefaults.standard.data(forKey: storageKey) else { return [] }
        do {
            return try JSONDecoder().decode([PendingMessage].self, from: data)
        } catch {
            return []
        }
    }
}

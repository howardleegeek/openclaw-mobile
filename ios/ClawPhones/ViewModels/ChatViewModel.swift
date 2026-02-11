//
//  ChatViewModel.swift
//  ClawPhones
//

import Network
import SwiftUI
import UIKit

@MainActor
final class ChatViewModel: ObservableObject {
    @Published var messages: [Message] = []
    @Published var isLoading: Bool = false
    @Published var errorMessage: String?
    @Published var conversationTitle: String?
    @Published var pendingFiles: [PendingFile] = []
    @Published private(set) var isOnline: Bool = true

    private(set) var conversationId: String?

    private let queueStore = MessageQueue.shared
    private let cacheStore = ConversationCache.shared
    private let pathMonitor = NWPathMonitor()
    private let monitorQueue = DispatchQueue(label: "ai.clawphones.network-monitor")
    private var isFlushingQueue = false
    private var hiddenHistory: [Message] = []
    private var isLoadingOlderMessages = false
    private var memoryWarningObserver: NSObjectProtocol?

    private let messagePageSize = 80
    private let maxPendingFiles = 3
    private let maxImageBytes = 10 * 1024 * 1024
    private let maxFileBytes = 20 * 1024 * 1024
    private let allowedFileMimeTypes: Set<String> = [
        "application/pdf",
        "text/plain",
        "text/csv",
        "application/json",
        "text/markdown"
    ]

    struct PendingFile: Identifiable, Hashable {
        let id: String
        let data: Data
        let filename: String
        let mimeType: String
        let thumbnail: UIImage?

        var size: Int { data.count }
    }

    static let defaultSystemPrompt = """
    你是 ClawPhones AI 助手，由 Oyster Labs 开发。你聪明、友好、高效。
    - 用用户的语言回复（中文问中文答，英文问英文答）
    - 回复简洁有用，避免冗长
    - 允许使用 Markdown（加粗、斜体、代码、链接、列表）让内容更清晰
    """

    init(conversationId: String? = nil) {
        self.conversationId = conversationId
        if let conversationId, !conversationId.isEmpty {
            restoreQueuedMessages(for: conversationId)
        } else {
            let pending = queueStore.listWithoutConversation()
            for item in pending {
                upsertQueuedUserMessage(item)
            }
        }
        configureNetworkMonitor()
        observeMemoryWarnings()
    }

    deinit {
        pathMonitor.cancel()
        if let memoryWarningObserver {
            NotificationCenter.default.removeObserver(memoryWarningObserver)
        }
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
            AnalyticsService.shared.track(
                "conversation_created",
                properties: ["conversation_id": conversation.id]
            )
            messages = []
            hiddenHistory.removeAll(keepingCapacity: false)

            queueStore.assignConversationIdToEmpty(conversation.id)
            restoreQueuedMessages(for: conversation.id)
            let now = Int(Date().timeIntervalSince1970)
            await cacheStore.upsertConversation(
                id: conversation.id,
                title: conversation.title,
                createdAt: conversation.createdAt,
                updatedAt: conversation.createdAt > 0 ? conversation.createdAt : now,
                lastMessage: nil,
                messageCount: 0
            )
            await cacheStore.replaceMessages(conversationId: conversation.id, messages: [])
            await flushPendingMessages()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func loadConversation(id: String) async {
        errorMessage = nil
        if conversationId != id {
            clearConversationStateForSwitch()
        }

        let cachedMessages = await cacheStore.loadMessages(conversationId: id)
        if !cachedMessages.isEmpty {
            applyPagedMessages(cachedMessages)
            conversationId = id
        }
        let cachedConversations = await cacheStore.loadConversations()
        if let cachedSummary = cachedConversations.first(where: { $0.id == id }) {
            conversationTitle = cachedSummary.title ?? conversationTitle ?? "Untitled"
        }

        isLoading = true
        defer { isLoading = false }

        do {
            let detail = try await OpenClawAPI.shared.getConversation(id: id)
            conversationId = detail.id
            conversationTitle = detail.title ?? "Untitled"
            applyPagedMessages(detail.messages)

            let latestTs = detail.messages.last?.createdAt ?? detail.createdAt
            let lastMessage = detail.messages.last?.content
            await cacheStore.upsertConversation(
                id: detail.id,
                title: detail.title,
                createdAt: detail.createdAt,
                updatedAt: latestTs,
                lastMessage: lastMessage,
                messageCount: detail.messages.count
            )
            await cacheStore.replaceMessages(conversationId: detail.id, messages: detail.messages)
            restoreQueuedMessages(for: id)
            await flushPendingMessages()
        } catch {
            if messages.isEmpty {
                errorMessage = error.localizedDescription
            }
        }
    }

    func addPendingFile(data: Data, filename: String, mimeType: String, thumbnail: UIImage?) {
        guard pendingFiles.count < maxPendingFiles else {
            errorMessage = "最多只能添加 3 个附件"
            return
        }

        let normalizedMime = normalizedAttachmentMimeType(mimeType, filename: filename)
        let isImage = normalizedMime.hasPrefix("image/")
        let maxBytes = isImage ? maxImageBytes : maxFileBytes
        guard data.count <= maxBytes else {
            errorMessage = "文件过大，请压缩后重试"
            return
        }

        if !isImage && !allowedFileMimeTypes.contains(normalizedMime) {
            errorMessage = "不支持的文件类型"
            return
        }

        let safeName = filename.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalizedName = safeName.isEmpty ? "upload.bin" : safeName

        pendingFiles.append(
            PendingFile(
                id: UUID().uuidString,
                data: data,
                filename: normalizedName,
                mimeType: normalizedMime,
                thumbnail: thumbnail
            )
        )
    }

    private func normalizedAttachmentMimeType(_ mimeType: String, filename: String) -> String {
        let raw = mimeType.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if raw == "text/x-markdown" {
            return "text/markdown"
        }
        if !raw.isEmpty, raw != "application/octet-stream" {
            return raw
        }
        let ext = (filename as NSString).pathExtension.lowercased()
        switch ext {
        case "pdf":
            return "application/pdf"
        case "txt":
            return "text/plain"
        case "csv":
            return "text/csv"
        case "json":
            return "application/json"
        case "md", "markdown":
            return "text/markdown"
        case "jpg", "jpeg":
            return "image/jpeg"
        case "png":
            return "image/png"
        case "gif":
            return "image/gif"
        case "webp":
            return "image/webp"
        default:
            return raw.isEmpty ? "application/octet-stream" : raw
        }
    }

    func removePendingFile(id: String) {
        pendingFiles.removeAll { $0.id == id }
    }

    func sendMessage(text: String) async {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let filesToSend = pendingFiles
        guard !trimmed.isEmpty || !filesToSend.isEmpty else { return }
        guard !isLoading else { return }

        errorMessage = nil
        AnalyticsService.shared.track(
            "message_sent",
            properties: [
                "conversation_id": conversationId ?? "",
                "message_length": trimmed.count,
                "queued": !isOnline,
                "attachment_count": filesToSend.count
            ]
        )

        if !isOnline {
            if !filesToSend.isEmpty {
                errorMessage = "附件上传需要网络连接"
                return
            }
            queueMessageForLater(trimmed)
            return
        }

        if conversationId == nil {
            await startNewConversation()
        }

        guard let cid = conversationId else {
            queueMessageForLater(trimmed)
            return
        }

        var uploadedFileIds: [String] = []
        if !filesToSend.isEmpty {
            for file in filesToSend {
                guard let fileId = await uploadFile(data: file.data, filename: file.filename, mimeType: file.mimeType) else {
                    pendingFiles = filesToSend
                    return
                }
                uploadedFileIds.append(fileId)
            }
            pendingFiles.removeAll(keepingCapacity: false)
        }

        let now = Int(Date().timeIntervalSince1970)
        let localDisplayContent = buildLocalUserContent(
            text: trimmed,
            files: filesToSend,
            uploadedFileIds: uploadedFileIds
        )
        let userMessage = Message(id: UUID().uuidString, role: .user, content: localDisplayContent, createdAt: now)
        messages.append(userMessage)

        let placeholderId = UUID().uuidString
        let placeholder = Message(id: placeholderId, role: .assistant, content: "", createdAt: now)
        messages.append(placeholder)
        await persistCacheSnapshot(conversationId: cid)

        isLoading = true
        defer { isLoading = false }

        let success = await streamOrFallbackChat(
            conversationId: cid,
            prompt: trimmed,
            fileIds: uploadedFileIds,
            placeholderId: placeholderId
        )

        if !success && !isOnline {
            if uploadedFileIds.isEmpty {
                queueExistingUserMessage(messageId: userMessage.id, text: trimmed, conversationId: cid)
            }
        }
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

        _ = await streamOrFallbackChat(conversationId: cid, prompt: prompt, fileIds: nil, placeholderId: placeholderId)
    }

    func deleteMessage(messageId: String) {
        messages.removeAll { $0.id == messageId }
        persistCacheSnapshotIfPossible()
    }

    func retryQueuedMessage(messageId: String) {
        guard let index = messages.firstIndex(where: { $0.id == messageId }) else { return }
        let message = messages[index]
        guard let queueId = message.localQueueId else { return }

        guard isOnline else {
            errorMessage = "当前离线，请联网后重试"
            return
        }

        queueStore.resetForManualRetry(id: queueId)
        setQueuedMessageState(queueId: queueId, state: .sending, retryCount: 0)

        Task { [weak self] in
            await self?.flushPendingMessages()
        }
    }

    func loadOlderMessagesIfNeeded(anchorMessageId: String) {
        guard !isLoadingOlderMessages else { return }
        guard !hiddenHistory.isEmpty else { return }
        guard messages.first?.id == anchorMessageId else { return }

        isLoadingOlderMessages = true
        defer { isLoadingOlderMessages = false }

        let nextStart = max(0, hiddenHistory.count - messagePageSize)
        let olderChunk = Array(hiddenHistory[nextStart..<hiddenHistory.count])
        hiddenHistory.removeSubrange(nextStart..<hiddenHistory.count)
        messages.insert(contentsOf: olderChunk, at: 0)
    }

    // MARK: - Queue / Network

    private func configureNetworkMonitor() {
        pathMonitor.pathUpdateHandler = { [weak self] path in
            Task { @MainActor in
                guard let self else { return }
                let online = path.status == .satisfied
                self.isOnline = online
                if online {
                    await self.flushPendingMessages()
                }
            }
        }
        pathMonitor.start(queue: monitorQueue)
    }

    private func queueMessageForLater(_ text: String) {
        let pending = queueStore.enqueue(message: text, conversationId: conversationId)
        upsertQueuedUserMessage(pending)
        persistCacheSnapshotIfPossible()
    }

    private func queueExistingUserMessage(messageId: String, text: String, conversationId: String?) {
        let pending = queueStore.enqueue(message: text, conversationId: conversationId)
        if let index = messages.firstIndex(where: { $0.id == messageId }) {
            let current = messages[index]
            messages[index] = Message(
                id: current.id,
                role: current.role,
                content: current.content,
                createdAt: current.createdAt,
                deliveryState: .sending,
                localQueueId: pending.id,
                retryCount: 0
            )
            persistCacheSnapshotIfPossible()
            return
        }
        upsertQueuedUserMessage(pending)
        persistCacheSnapshotIfPossible()
    }

    private func restoreQueuedMessages(for conversationId: String) {
        let pending = queueStore.list(for: conversationId)
        for item in pending {
            upsertQueuedUserMessage(item)
        }
    }

    private func upsertQueuedUserMessage(_ pending: MessageQueue.PendingMessage) {
        let deliveryState: Message.DeliveryState = {
            switch pending.status {
            case .failed:
                return .failed
            case .pending, .sending:
                return .sending
            }
        }()

        if let index = messages.firstIndex(where: { $0.localQueueId == pending.id }) {
            let current = messages[index]
            messages[index] = Message(
                id: current.id,
                role: .user,
                content: pending.message,
                createdAt: current.createdAt,
                deliveryState: deliveryState,
                localQueueId: pending.id,
                retryCount: pending.retryCount
            )
            return
        }

        let localMessage = Message(
            id: "queued-\(pending.id)",
            role: .user,
            content: pending.message,
            createdAt: pending.createdAt,
            deliveryState: deliveryState,
            localQueueId: pending.id,
            retryCount: pending.retryCount
        )
        messages.append(localMessage)
    }

    private func setQueuedMessageState(queueId: String, state: Message.DeliveryState, retryCount: Int) {
        guard let index = messages.firstIndex(where: { $0.localQueueId == queueId }) else { return }
        let current = messages[index]
        messages[index] = Message(
            id: current.id,
            role: current.role,
            content: current.content,
            createdAt: current.createdAt,
            deliveryState: state,
            localQueueId: queueId,
            retryCount: max(0, retryCount)
        )
        persistCacheSnapshotIfPossible()
    }

    private func markQueuedMessageSent(queueId: String) {
        guard let index = messages.firstIndex(where: { $0.localQueueId == queueId }) else { return }
        let current = messages[index]
        messages[index] = Message(
            id: current.id,
            role: current.role,
            content: current.content,
            createdAt: current.createdAt,
            deliveryState: .sent,
            localQueueId: nil,
            retryCount: 0
        )
        persistCacheSnapshotIfPossible()
    }

    private func flushPendingMessages() async {
        guard isOnline else { return }
        guard !isFlushingQueue else { return }
        isFlushingQueue = true
        defer { isFlushingQueue = false }

        while isOnline {
            guard var pending = queueStore.nextPending(for: conversationId) else { return }

            var targetConversationId = pending.conversationId.trimmingCharacters(in: .whitespacesAndNewlines)
            if targetConversationId.isEmpty {
                if let existing = conversationId, !existing.isEmpty {
                    targetConversationId = existing
                } else {
                    do {
                        let conversation = try await OpenClawAPI.shared.createConversation(
                            systemPrompt: Self.defaultSystemPrompt
                        )
                        conversationId = conversation.id
                        conversationTitle = conversation.title ?? "New Chat"
                        AnalyticsService.shared.track(
                            "conversation_created",
                            properties: ["conversation_id": conversation.id]
                        )
                        targetConversationId = conversation.id
                        let now = Int(Date().timeIntervalSince1970)
                        await cacheStore.upsertConversation(
                            id: conversation.id,
                            title: conversation.title,
                            createdAt: conversation.createdAt,
                            updatedAt: conversation.createdAt > 0 ? conversation.createdAt : now,
                            lastMessage: nil,
                            messageCount: 0
                        )
                    } catch {
                        return
                    }
                }
                queueStore.updateConversationId(id: pending.id, conversationId: targetConversationId)
                queueStore.assignConversationIdToEmpty(targetConversationId)
                pending.conversationId = targetConversationId
            }

            queueStore.markSending(id: pending.id)
            pending.status = .sending
            upsertQueuedUserMessage(pending)

            let success = await sendQueuedPendingMessage(
                pendingId: pending.id,
                conversationId: targetConversationId,
                prompt: pending.message
            )
            if success {
                queueStore.remove(id: pending.id)
                markQueuedMessageSent(queueId: pending.id)
                continue
            }

            let retryCount = queueStore.incrementRetryCount(id: pending.id)
            if retryCount >= 3 {
                queueStore.markFailed(id: pending.id)
                setQueuedMessageState(queueId: pending.id, state: .failed, retryCount: retryCount)
                continue
            }

            queueStore.markPending(id: pending.id)
            setQueuedMessageState(queueId: pending.id, state: .sending, retryCount: retryCount)
            try? await Task.sleep(nanoseconds: 300_000_000)
        }
    }

    private func sendQueuedPendingMessage(pendingId: String, conversationId: String, prompt: String) async -> Bool {
        let now = Int(Date().timeIntervalSince1970)
        let placeholderId = "queue-assistant-\(pendingId)-\(UUID().uuidString)"
        let placeholder = Message(id: placeholderId, role: .assistant, content: "", createdAt: now)
        messages.append(placeholder)
        return await streamOrFallbackChat(
            conversationId: conversationId,
            prompt: prompt,
            fileIds: nil,
            placeholderId: placeholderId
        )
    }

    // MARK: - Streaming Helpers

    func uploadFile(data: Data, filename: String, mimeType: String) async -> String? {
        guard let cid = conversationId, !cid.isEmpty else {
            errorMessage = "会话未初始化，无法上传附件"
            return nil
        }
        do {
            let result = try await OpenClawAPI.shared.uploadFile(
                conversationId: cid,
                fileData: data,
                filename: filename,
                mimeType: mimeType
            )
            let fileId = result.fileId.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !fileId.isEmpty else {
                errorMessage = "附件上传失败：file_id 为空"
                return nil
            }
            return fileId
        } catch {
            errorMessage = error.localizedDescription
            return nil
        }
    }

    private func buildLocalUserContent(text: String, files: [PendingFile], uploadedFileIds: [String]) -> String {
        guard !files.isEmpty else { return text }
        var normalizedIds: [String] = []
        var filesPayload: [[String: Any]] = []
        let count = min(files.count, uploadedFileIds.count)
        for index in 0..<count {
            let file = files[index]
            let fileId = uploadedFileIds[index].trimmingCharacters(in: .whitespacesAndNewlines)
            guard !fileId.isEmpty else { continue }
            normalizedIds.append(fileId)
            filesPayload.append(
                [
                    "id": fileId,
                    "name": file.filename,
                    "size": file.size,
                    "type": file.mimeType,
                    "url": "/v1/files/\(fileId)"
                ]
            )
        }

        guard !normalizedIds.isEmpty else { return text }

        let meta: [String: Any] = [
            "file_ids": normalizedIds,
            "files": filesPayload
        ]
        let payloadData = (try? JSONSerialization.data(withJSONObject: meta, options: [])) ?? Data("{}".utf8)
        let payloadJSON = String(data: payloadData, encoding: .utf8) ?? "{}"
        return "[[MESSAGE_META]]\(payloadJSON)[[/MESSAGE_META]]\(text)"
    }

    private func formatBytes(_ size: Int) -> String {
        if size < 1024 {
            return "\(size) B"
        }
        if size < 1024 * 1024 {
            return String(format: "%.1f KB", Double(size) / 1024.0)
        }
        return String(format: "%.1f MB", Double(size) / (1024.0 * 1024.0))
    }

    private func streamOrFallbackChat(
        conversationId: String,
        prompt: String,
        fileIds: [String]?,
        placeholderId: String
    ) async -> Bool {
        var content = ""
        var didReceiveAnyChunk = false

        do {
            let stream = OpenClawAPI.shared.chatStream(conversationId: conversationId, message: prompt, fileIds: fileIds)
            for try await chunk in stream {
                didReceiveAnyChunk = true

                if chunk.done {
                    let finalContent = chunk.fullContent ?? content
                    let finalId = chunk.messageId ?? placeholderId
                    let now = Int(Date().timeIntervalSince1970)
                    let finalMessage = Message(id: finalId, role: .assistant, content: finalContent, createdAt: now)
                    replaceMessage(id: placeholderId, with: finalMessage)
                    await persistCacheSnapshot(conversationId: conversationId)
                    return true
                }

                if !chunk.delta.isEmpty {
                    content += chunk.delta
                    updateMessageContent(id: placeholderId, content: content)
                }
            }

            if didReceiveAnyChunk {
                deleteMessage(messageId: placeholderId)
                await persistCacheSnapshot(conversationId: conversationId)
                return false
            }
        } catch {
            CrashReporter.shared.reportNonFatal(error: error, action: "streaming_chat")
            deleteMessage(messageId: placeholderId)
            await persistCacheSnapshot(conversationId: conversationId)
            return false
        }

        do {
            let response = try await OpenClawAPI.shared.chat(
                conversationId: conversationId,
                message: prompt,
                fileIds: fileIds
            )
            let reply = Message(
                id: response.messageId,
                role: response.role,
                content: response.content,
                createdAt: response.createdAt
            )
            replaceMessage(id: placeholderId, with: reply)
            await persistCacheSnapshot(conversationId: conversationId)
            return true
        } catch {
            errorMessage = error.localizedDescription
            deleteMessage(messageId: placeholderId)
            await persistCacheSnapshot(conversationId: conversationId)
            return false
        }
    }

    private func updateMessageContent(id: String, content: String) {
        guard let index = messages.firstIndex(where: { $0.id == id }) else { return }
        let current = messages[index]
        messages[index] = Message(
            id: current.id,
            role: current.role,
            content: content,
            createdAt: current.createdAt,
            deliveryState: current.deliveryState,
            localQueueId: current.localQueueId,
            retryCount: current.retryCount
        )
    }

    private func replaceMessage(id: String, with newMessage: Message) {
        guard let index = messages.firstIndex(where: { $0.id == id }) else { return }
        messages[index] = newMessage
    }

    private func persistCacheSnapshotIfPossible() {
        guard let cid = conversationId, !cid.isEmpty else { return }
        let snapshot = currentConversationSnapshot()
        let title = conversationTitle
        Task {
            await persistCacheSnapshot(
                conversationId: cid,
                snapshot: snapshot,
                title: title
            )
        }
    }

    private func persistCacheSnapshot(conversationId: String) async {
        await persistCacheSnapshot(
            conversationId: conversationId,
            snapshot: currentConversationSnapshot(),
            title: conversationTitle
        )
    }

    private func persistCacheSnapshot(conversationId: String, snapshot: [Message], title: String?) async {
        let cacheable = normalizeMessagesForCache(snapshot)
        await cacheStore.replaceMessages(conversationId: conversationId, messages: cacheable)
        let lastMessage = cacheable.last?.content
        await cacheStore.upsertConversation(
            id: conversationId,
            title: title,
            updatedAt: Int(Date().timeIntervalSince1970),
            lastMessage: lastMessage,
            messageCount: cacheable.count
        )
    }

    private func applyPagedMessages(_ source: [Message]) {
        if source.count <= messagePageSize {
            hiddenHistory.removeAll(keepingCapacity: false)
            messages = source
            return
        }

        let splitIndex = max(0, source.count - messagePageSize)
        hiddenHistory = Array(source[..<splitIndex])
        messages = Array(source[splitIndex...])
    }

    private func currentConversationSnapshot() -> [Message] {
        hiddenHistory + messages
    }

    private func observeMemoryWarnings() {
        memoryWarningObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didReceiveMemoryWarningNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                MessageThumbnailCache.shared.clear()
                self.compactHiddenHistoryForMemoryPressure()
            }
        }
    }

    private func compactHiddenHistoryForMemoryPressure() {
        guard hiddenHistory.count > messagePageSize else { return }
        hiddenHistory = Array(hiddenHistory.suffix(messagePageSize))
    }

    private func clearConversationStateForSwitch() {
        messages.removeAll(keepingCapacity: false)
        hiddenHistory.removeAll(keepingCapacity: false)
        MessageThumbnailCache.shared.clear()
    }

    private func normalizeMessagesForCache(_ source: [Message]) -> [Message] {
        source.filter { message in
            let trimmed = message.content.trimmingCharacters(in: .whitespacesAndNewlines)
            return !trimmed.isEmpty
        }
    }
}

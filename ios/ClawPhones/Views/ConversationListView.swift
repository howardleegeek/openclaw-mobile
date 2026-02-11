//
//  ConversationListView.swift
//  ClawPhones
//

import SwiftUI
import Combine
import UIKit

struct ConversationListView: View {
    private struct ConversationRoute: Identifiable, Hashable {
        let id: String
    }

    @EnvironmentObject private var auth: AuthViewModel
    @StateObject private var viewModel = ConversationListViewModel()
    @State private var showNewChat: Bool = false
    @State private var activeConversationRoute: ConversationRoute?
    @State private var isImportingSharedPayload: Bool = false
    @Binding private var pendingSharedPayloadID: String?

    private static let monthDayFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "M月d日"
        return formatter
    }()

    init(pendingSharedPayloadID: Binding<String?> = .constant(nil)) {
        _pendingSharedPayloadID = pendingSharedPayloadID
    }

    var body: some View {
        conversationList
            .navigationTitle("ClawPhones")
            .toolbar {
                ToolbarItemGroup(placement: .navigationBarTrailing) {
                    Button {
                        showNewChat = true
                    } label: {
                        Image(systemName: "plus")
                    }

                    NavigationLink {
                        SettingsView()
                    } label: {
                        Image(systemName: "person.crop.circle")
                    }
                }
            }
            .navigationDestination(isPresented: $showNewChat) {
                ChatView(conversationId: nil)
            }
            .navigationDestination(item: $activeConversationRoute) { route in
                ChatView(conversationId: route.id)
            }
            .task(id: auth.isAuthenticated) {
                if auth.isAuthenticated {
                    await loadConversationsWithTracking()
                    await processPendingSharedPayloadIfNeeded()
                } else {
                    viewModel.conversations = []
                    viewModel.errorMessage = nil
                }
            }
            .onChange(of: pendingSharedPayloadID) { _ in
                Task {
                    await processPendingSharedPayloadIfNeeded()
                }
            }
            .refreshable {
                if auth.isAuthenticated {
                    await loadConversationsWithTracking()
                    let generator = UIImpactFeedbackGenerator(style: .light)
                    generator.impactOccurred()
                }
            }
            .onReceive(NotificationCenter.default.publisher(for: .clawPhonesConversationsDidChange)) { _ in
                guard auth.isAuthenticated else { return }
                Task { await loadConversationsWithTracking() }
            }
            .overlay {
                if viewModel.isLoading && viewModel.conversations.isEmpty {
                    ProgressView()
                }
            }
            .alert("Error", isPresented: Binding(
                get: { viewModel.errorMessage != nil },
                set: { newValue in
                    if !newValue {
                        viewModel.errorMessage = nil
                    }
                }
            )) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(viewModel.errorMessage ?? "")
            }
    }

    private var conversationList: some View {
        List {
            if viewModel.conversations.isEmpty && !viewModel.isLoading {
                ContentUnavailableView("No Conversations", systemImage: "message", description: Text("Tap + to start a new chat."))
            } else {
                ForEach(viewModel.conversations) { conversation in
                    conversationRow(for: conversation)
                }
                .onDelete(perform: delete)
            }
        }
    }

    private func conversationRow(for conversation: ConversationSummary) -> some View {
        NavigationLink {
            ChatView(conversationId: conversation.id)
        } label: {
            HStack(alignment: .top, spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(conversation.title?.isEmpty == false ? conversation.title! : "Untitled")
                        .font(.headline)
                        .lineLimit(1)

                    Text(previewText(for: conversation))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }

                Spacer(minLength: 0)

                Text(relativeTimeText(for: conversation))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding(.vertical, 4)
        }
    }

    private func delete(at offsets: IndexSet) {
        let ids = offsets.map { viewModel.conversations[$0].id }
        for id in ids {
            Task {
                await viewModel.deleteConversation(id: id)
            }
        }
    }

    private func previewText(for conversation: ConversationSummary) -> String {
        if let lastMessage = conversation.lastMessage?.trimmingCharacters(in: .whitespacesAndNewlines), !lastMessage.isEmpty {
            return lastMessage
        }

        if let title = conversation.title?.trimmingCharacters(in: .whitespacesAndNewlines), !title.isEmpty {
            return title
        }

        return "\(conversation.messageCount) messages"
    }

    private func relativeTimeText(for conversation: ConversationSummary) -> String {
        let timestamp = conversation.updatedAt ?? conversation.createdAt
        let date = Date(timeIntervalSince1970: TimeInterval(timestamp))
        let now = Date()
        let seconds = max(0, now.timeIntervalSince(date))

        if seconds < 60 {
            return "刚刚"
        }

        let minutes = Int(seconds / 60)
        if minutes < 60 {
            return "\(minutes)分钟前"
        }

        let hours = Int(seconds / 3600)
        if Calendar.current.isDate(date, inSameDayAs: now), hours < 24 {
            return "\(hours)小时前"
        }

        if Calendar.current.isDateInYesterday(date) {
            return "昨天"
        }

        return Self.monthDayFormatter.string(from: date)
    }

    private func loadConversationsWithTracking() async {
        CrashReporter.shared.setLastAction("loading_conversations")
        await viewModel.loadConversations()
    }

    private func processPendingSharedPayloadIfNeeded() async {
        guard auth.isAuthenticated else { return }
        guard !isImportingSharedPayload else { return }
        guard let payloadID = pendingSharedPayloadID else { return }
        guard let payload = SharePayloadBridge.payload(id: payloadID) else {
            pendingSharedPayloadID = nil
            return
        }

        isImportingSharedPayload = true
        defer { isImportingSharedPayload = false }

        do {
            let conversation = try await OpenClawAPI.shared.createConversation(systemPrompt: ChatViewModel.defaultSystemPrompt)
            AnalyticsService.shared.track(
                "conversation_created",
                properties: [
                    "conversation_id": conversation.id,
                    "source": "shared_payload"
                ]
            )

            do {
                _ = try await OpenClawAPI.shared.chat(conversationId: conversation.id, message: payload.content)
            } catch {
                viewModel.errorMessage = "会话已创建，但分享内容发送失败：\(error.localizedDescription)"
            }

            SharePayloadBridge.clearPayload(id: payloadID)
            pendingSharedPayloadID = nil
            NotificationCenter.default.post(name: .clawPhonesConversationsDidChange, object: nil)
            await loadConversationsWithTracking()
            activeConversationRoute = ConversationRoute(id: conversation.id)
        } catch {
            viewModel.errorMessage = "导入分享内容失败：\(error.localizedDescription)"
        }
    }
}

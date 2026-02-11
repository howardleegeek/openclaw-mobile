//
//  ChatView.swift
//  ClawPhones
//

import SwiftUI

struct ChatView: View {
    @StateObject private var viewModel: ChatViewModel
    @State private var inputText: String = ""
    @State private var showCopiedToast: Bool = false
    @State private var showCoverageMap: Bool = false
    @State private var copiedToastTask: Task<Void, Never>?
    @State private var lastObservedLastMessageId: String?

    private static let clockFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm"
        return formatter
    }()

    private static let monthDayClockFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "M/d HH:mm"
        return formatter
    }()

    init(conversationId: String?) {
        _viewModel = StateObject(wrappedValue: ChatViewModel(conversationId: conversationId))
    }

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 12) {
                        ForEach(viewModel.messages.indices, id: \.self) { index in
                            if viewModel.messages.indices.contains(index) {
                                let message = viewModel.messages[index]
                                MessageRow(
                                    message: message,
                                    timestampText: timestampText(for: index),
                                    onRetry: message.role == .user && message.deliveryState == .failed ? {
                                        viewModel.retryQueuedMessage(messageId: message.id)
                                    } : nil,
                                    onCopy: handleCopiedMessage,
                                    onRegenerate: message.role == .assistant ? {
                                        Task { await viewModel.regenerateAssistantMessage(messageId: message.id) }
                                    } : nil,
                                    onDelete: {
                                        viewModel.deleteMessage(messageId: message.id)
                                    }
                                )
                                    .id(message.id)
                                    .onAppear {
                                        if index <= 2 {
                                            viewModel.loadOlderMessagesIfNeeded(anchorMessageId: message.id)
                                        }
                                    }
                            }
                        }
                    }
                    .padding(.horizontal)
                    .padding(.vertical, 12)
                }
                .scrollDismissesKeyboard(.interactively)
                .onChange(of: viewModel.messages.count) { _ in
                    let currentLastId = viewModel.messages.last?.id
                    if currentLastId != nil && currentLastId != lastObservedLastMessageId {
                        scrollToBottom(proxy, animated: true)
                    }
                    lastObservedLastMessageId = currentLastId
                }
                .onChange(of: viewModel.messages.last?.content ?? "") { _ in
                    scrollToBottom(proxy, animated: false)
                }
                .onAppear {
                    lastObservedLastMessageId = viewModel.messages.last?.id
                    scrollToBottom(proxy, animated: false)
                }
            }

            Divider()

            if showCoverageMap {
                CoverageMapView()
                    .frame(height: 250)
                    .padding(.horizontal)
                    .padding(.top, 8)
                    .padding(.bottom, 4)
                    .transition(.move(edge: .bottom).combined(with: .opacity))

                Divider()
            }

            ChatInputBar(
                text: $inputText,
                isLoading: viewModel.isLoading,
                pendingFiles: viewModel.pendingFiles,
                onRemovePendingFile: { fileId in
                    viewModel.removePendingFile(id: fileId)
                },
                onAttachmentPicked: { data, filename, mimeType, thumbnail in
                    viewModel.addPendingFile(
                        data: data,
                        filename: filename,
                        mimeType: mimeType,
                        thumbnail: thumbnail
                    )
                },
                onSend: {
                let textToSend = inputText
                inputText = ""
                CrashReporter.shared.setLastAction("sending_message")
                Task {
                    await viewModel.sendMessage(text: textToSend)
                }
                }
            )
        }
        .navigationTitle(viewModel.conversationTitle?.isEmpty == false ? (viewModel.conversationTitle ?? "") : "Chat")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button {
                    withAnimation(.easeInOut(duration: 0.2)) {
                        showCoverageMap.toggle()
                    }
                } label: {
                    Image(systemName: showCoverageMap ? "map.fill" : "map")
                }
                .accessibilityLabel("Toggle Coverage Map")
            }
        }
        .overlay(alignment: .bottom) {
            if showCopiedToast {
                Text("已复制")
                    .font(.caption)
                    .foregroundStyle(.white)
                    .padding(.vertical, 8)
                    .padding(.horizontal, 16)
                    .background(Color.black.opacity(0.7))
                    .clipShape(Capsule())
                    .padding(.bottom, 16)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .allowsHitTesting(false)
            }
        }
        .task {
            if let cid = viewModel.conversationId {
                await viewModel.loadConversation(id: cid)
            } else {
                await viewModel.startNewConversation()
            }
        }
        .onDisappear {
            copiedToastTask?.cancel()
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

    private func scrollToBottom(_ proxy: ScrollViewProxy, animated: Bool) {
        guard let lastId = viewModel.messages.last?.id else { return }
        if animated {
            withAnimation(.easeOut(duration: 0.2)) {
                proxy.scrollTo(lastId, anchor: .bottom)
            }
        } else {
            proxy.scrollTo(lastId, anchor: .bottom)
        }
    }

    private func handleCopiedMessage() {
        copiedToastTask?.cancel()

        withAnimation(.easeOut(duration: 0.2)) {
            showCopiedToast = true
        }

        copiedToastTask = Task {
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            guard !Task.isCancelled else { return }

            await MainActor.run {
                withAnimation(.easeIn(duration: 0.2)) {
                    showCopiedToast = false
                }
            }
        }
    }

    private func timestampText(for index: Int) -> String? {
        guard viewModel.messages.indices.contains(index) else { return nil }

        let current = messageDate(for: viewModel.messages[index])
        if index > 0 {
            let previous = messageDate(for: viewModel.messages[index - 1])
            if current.timeIntervalSince(previous) <= 5 * 60 {
                return nil
            }
        }

        return formattedTimestamp(for: current)
    }

    private func formattedTimestamp(for date: Date) -> String {
        if Calendar.current.isDateInToday(date) {
            return Self.clockFormatter.string(from: date)
        }

        if Calendar.current.isDateInYesterday(date) {
            return "昨天 \(Self.clockFormatter.string(from: date))"
        }

        return Self.monthDayClockFormatter.string(from: date)
    }

    private func messageDate(for message: Message) -> Date {
        Date(timeIntervalSince1970: TimeInterval(message.createdAt))
    }

    private func formatFileSize(_ size: Int) -> String {
        if size < 1024 {
            return "\(size) B"
        }
        if size < 1024 * 1024 {
            return String(format: "%.1f KB", Double(size) / 1024.0)
        }
        return String(format: "%.1f MB", Double(size) / (1024.0 * 1024.0))
    }
}

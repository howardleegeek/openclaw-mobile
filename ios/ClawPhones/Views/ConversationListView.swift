//
//  ConversationListView.swift
//  ClawPhones
//

import SwiftUI

struct ConversationListView: View {
    @StateObject private var viewModel = ConversationListViewModel()
    @State private var showNewChat: Bool = false

    var body: some View {
        List {
            if viewModel.conversations.isEmpty && !viewModel.isLoading {
                ContentUnavailableView("No Conversations", systemImage: "message", description: Text("Tap + to start a new chat."))
            } else {
                ForEach(viewModel.conversations) { conversation in
                    NavigationLink {
                        ChatView(conversationId: conversation.id)
                    } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(conversation.title?.isEmpty == false ? conversation.title! : "Untitled")
                                .font(.headline)
                                .lineLimit(1)

                            Text("\(conversation.messageCount) messages")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        .padding(.vertical, 4)
                    }
                }
                .onDelete(perform: delete)
            }
        }
        .navigationTitle("ClawPhones")
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button {
                    showNewChat = true
                } label: {
                    Image(systemName: "plus")
                }
            }
        }
        .navigationDestination(isPresented: $showNewChat) {
            ChatView(conversationId: nil)
        }
        .task {
            await viewModel.loadConversations()
        }
        .refreshable {
            await viewModel.loadConversations()
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

    private func delete(at offsets: IndexSet) {
        let ids = offsets.map { viewModel.conversations[$0].id }
        for id in ids {
            Task {
                await viewModel.deleteConversation(id: id)
            }
        }
    }
}

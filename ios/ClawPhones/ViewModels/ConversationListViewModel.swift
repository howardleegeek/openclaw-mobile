//
//  ConversationListViewModel.swift
//  ClawPhones
//

import SwiftUI

@MainActor
final class ConversationListViewModel: ObservableObject {
    @Published var conversations: [ConversationSummary] = []
    @Published var isLoading: Bool = false
    @Published var errorMessage: String?

    func loadConversations() async {
        errorMessage = nil
        isLoading = true
        defer { isLoading = false }

        do {
            conversations = try await OpenClawAPI.shared.listConversations(limit: 20, offset: 0)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func deleteConversation(id: String) async {
        errorMessage = nil

        do {
            let deleted = try await OpenClawAPI.shared.deleteConversation(id: id)
            if deleted {
                conversations.removeAll { $0.id == id }
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

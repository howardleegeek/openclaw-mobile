//
//  ContentView.swift
//  ClawPhones
//

import SwiftUI

struct ContentView: View {
    @StateObject private var auth = AuthViewModel()

    private enum Tab: Hashable {
        case chat
        case settings
    }

    @State private var selectedTab: Tab = .chat

    var body: some View {
        TabView(selection: $selectedTab) {
            NavigationStack {
                ConversationListView()
            }
            .tabItem {
                Label("聊天", systemImage: "message")
            }
            .tag(Tab.chat)

            NavigationStack {
                SettingsView()
            }
            .tabItem {
                Label("设置", systemImage: "gearshape")
            }
            .tag(Tab.settings)
        }
        .environmentObject(auth)
        .fullScreenCover(isPresented: Binding(
            get: { !auth.isAuthenticated },
            set: { _ in }
        )) {
            NavigationStack {
                LoginView()
            }
            .environmentObject(auth)
        }
        .onReceive(NotificationCenter.default.publisher(for: Notification.Name("ClawPhonesAuthExpired"))) { _ in
            auth.refreshAuthState()
        }
        .onAppear {
            auth.refreshAuthState()
        }
    }
}

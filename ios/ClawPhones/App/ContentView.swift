//
//  ContentView.swift
//  ClawPhones
//

import SwiftUI

struct ContentView: View {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var auth = AuthViewModel()
    @AppStorage("hasSeenOnboarding") private var hasSeenOnboarding: Bool = false
    @AppStorage(BiometricAuthService.lockEnabledStorageKey) private var biometricLockEnabled: Bool = false

    private enum Tab: Hashable {
        case chat
        case settings
    }

    @State private var selectedTab: Tab = .chat
    @State private var pendingSharedPayloadID: String?
    @State private var hasLoadedInitialSharedPayload = false
    @State private var isAppLocked = false
    @State private var shouldPromptForUnlock = false
    @State private var isAuthenticatingForUnlock = false

    var body: some View {
        TabView(selection: $selectedTab) {
            NavigationStack {
                ConversationListView(pendingSharedPayloadID: $pendingSharedPayloadID)
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
            get: { hasSeenOnboarding && !auth.isAuthenticated },
            set: { _ in }
        )) {
            NavigationStack {
                LoginView()
            }
            .environmentObject(auth)
        }
        .fullScreenCover(isPresented: Binding(
            get: { !hasSeenOnboarding },
            set: { _ in }
        )) {
            OnboardingView {
                hasSeenOnboarding = true
            }
            .interactiveDismissDisabled()
        }
        .onReceive(NotificationCenter.default.publisher(for: Notification.Name("ClawPhonesAuthExpired"))) { _ in
            auth.refreshAuthState()
        }
        .onChange(of: scenePhase) { _, newPhase in
            handleScenePhaseChange(newPhase)
        }
        .onChange(of: biometricLockEnabled) { _, isEnabled in
            if isEnabled {
                lockForBiometricAuth()
                if scenePhase == .active {
                    beginBiometricUnlock()
                }
            } else {
                isAppLocked = false
                shouldPromptForUnlock = false
            }
        }
        .onOpenURL { url in
            guard let payloadID = SharePayloadBridge.payloadID(from: url) else { return }
            selectedTab = .chat
            pendingSharedPayloadID = payloadID
        }
        .onAppear {
            auth.refreshAuthState()
            if biometricLockEnabled {
                lockForBiometricAuth()
                beginBiometricUnlock()
            }

            guard !hasLoadedInitialSharedPayload else { return }
            hasLoadedInitialSharedPayload = true

            if let payloadID = SharePayloadBridge.latestPayloadID() {
                pendingSharedPayloadID = payloadID
            }
        }
        .overlay {
            if isAppLocked {
                biometricLockOverlay
            }
        }
    }

    private var biometricLockOverlay: some View {
        ZStack {
            Color(uiColor: .systemBackground)
                .ignoresSafeArea()

            VStack(spacing: 14) {
                Image(systemName: "lock.shield.fill")
                    .font(.system(size: 40, weight: .semibold))
                    .foregroundStyle(.tint)

                Text("请验证身份")
                    .font(.title3.weight(.semibold))

                Text("使用\(BiometricAuthService.shared.currentBiometryInfo().displayName)或设备密码继续。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)

                if isAuthenticatingForUnlock {
                    ProgressView()
                        .padding(.top, 6)
                } else {
                    Button("重试验证") {
                        beginBiometricUnlock()
                    }
                    .buttonStyle(.borderedProminent)
                    .padding(.top, 6)
                }
            }
            .padding(.horizontal, 24)
        }
    }

    private func handleScenePhaseChange(_ phase: ScenePhase) {
        guard biometricLockEnabled else { return }

        switch phase {
        case .background:
            lockForBiometricAuth()
        case .active:
            if shouldPromptForUnlock || isAppLocked {
                beginBiometricUnlock()
            }
        default:
            break
        }
    }

    private func lockForBiometricAuth() {
        isAppLocked = true
        shouldPromptForUnlock = true
    }

    private func beginBiometricUnlock() {
        guard biometricLockEnabled else { return }
        guard !isAuthenticatingForUnlock else { return }

        let info = BiometricAuthService.shared.currentBiometryInfo()
        guard info.isAvailable else {
            biometricLockEnabled = false
            isAppLocked = false
            shouldPromptForUnlock = false
            return
        }

        isAuthenticatingForUnlock = true
        Task {
            let success = await BiometricAuthService.shared.authenticateForAppUnlock()
            await MainActor.run {
                isAuthenticatingForUnlock = false
                if success {
                    isAppLocked = false
                    shouldPromptForUnlock = false
                } else {
                    isAppLocked = true
                    shouldPromptForUnlock = true
                }
            }
        }
    }
}

//
//  SettingsViewModel.swift
//  ClawPhones
//

import SwiftUI

extension Notification.Name {
    static let clawPhonesAuthDidChange = Notification.Name("ai.clawphones.auth.did_change")
    static let clawPhonesConversationsDidChange = Notification.Name("ai.clawphones.conversations.did_change")
}

@MainActor
final class SettingsViewModel: ObservableObject {
    enum PlanTier: String, CaseIterable, Identifiable, Codable {
        case free = "Free"
        case pro = "Pro"
        case max = "Max"

        var id: String { rawValue }
    }

    enum Language: String, CaseIterable, Identifiable, Codable {
        case auto
        case zh
        case en

        var id: String { rawValue }

        var displayName: String {
            switch self {
            case .auto: return "自动"
            case .zh: return "中文"
            case .en: return "English"
            }
        }
    }

    enum Persona: String, CaseIterable, Identifiable, Codable {
        case general
        case coding
        case writing
        case translation
        case custom

        var id: String { rawValue }

        var displayName: String {
            switch self {
            case .general: return "🧠 通用助手"
            case .coding: return "💻 编程专家"
            case .writing: return "✍️ 写作助手"
            case .translation: return "🌍 翻译官"
            case .custom: return "⚙️ 自定义"
            }
        }
    }

    struct UserProfile: Equatable {
        var userId: String
        var email: String
        var name: String
        var tier: PlanTier
        var language: Language

        static let mock = UserProfile(
            userId: "mock_user",
            email: "user@example.com",
            name: "Claw User",
            tier: .free,
            language: .auto
        )
    }

    struct PlanInfo: Equatable {
        var tier: PlanTier
        var dailyLimit: Int
        var usedToday: Int

        static let mock = PlanInfo(tier: .free, dailyLimit: 50, usedToday: 12)
    }

    struct AIConfig: Equatable {
        var persona: Persona
        var customPrompt: String
        var temperature: Double

        static let mock = AIConfig(persona: .general, customPrompt: "", temperature: 0.7)
    }

    @Published var profile: UserProfile = .mock
    @Published var plan: PlanInfo = .mock
    @Published var aiConfig: AIConfig = .mock

    @Published var isLoading: Bool = false
    @Published var errorMessage: String?
    @Published var passwordChangeSucceeded: Bool = false
    @Published var isExportingData: Bool = false

    func loadProfile() async {
        errorMessage = nil
        isLoading = true
        defer { isLoading = false }

        do {
            let payload = try await OpenClawAPI.shared.getUserProfile()
            profile = UserProfile(
                userId: payload.userId,
                email: payload.email,
                name: payload.name ?? profile.name,
                tier: PlanTier(rawValue: payload.tier) ?? profile.tier,
                language: Language(rawValue: payload.language ?? profile.language.rawValue) ?? profile.language
            )
        } catch {
            // TODO: backend may not be ready yet; keep mock so UI can render.
            if profile.email.isEmpty {
                profile = .mock
            }
            errorMessage = error.localizedDescription
        }
    }

    func updateName(name: String) async {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }

        errorMessage = nil
        isLoading = true
        defer { isLoading = false }

        let previous = profile
        profile.name = trimmed

        do {
            let payload = try await OpenClawAPI.shared.updateUserProfile(name: trimmed, language: nil)
            profile.name = payload.name ?? trimmed
            AnalyticsService.shared.track(
                "settings_changed",
                properties: ["setting": "name"]
            )
        } catch {
            // TODO: backend may not be ready yet; keep local update.
            profile = previous
            errorMessage = error.localizedDescription
        }
    }

    func updateLanguage(lang: Language) async {
        errorMessage = nil
        isLoading = true
        defer { isLoading = false }

        let previous = profile
        profile.language = lang

        do {
            let payload = try await OpenClawAPI.shared.updateUserProfile(name: nil, language: lang.rawValue)
            profile.language = Language(rawValue: payload.language ?? lang.rawValue) ?? lang
            AnalyticsService.shared.track(
                "settings_changed",
                properties: [
                    "setting": "language",
                    "language": lang.rawValue
                ]
            )
        } catch {
            // TODO: backend may not be ready yet; keep local update.
            profile = previous
            errorMessage = error.localizedDescription
        }
    }

    func updatePassword(old: String, new: String) async {
        await changePassword(oldPassword: old, newPassword: new)
    }

    func changePassword(oldPassword: String, newPassword: String) async {
        errorMessage = nil
        passwordChangeSucceeded = false
        isLoading = true
        defer { isLoading = false }

        do {
            _ = try await OpenClawAPI.shared.updatePassword(oldPassword: oldPassword, newPassword: newPassword)
            passwordChangeSucceeded = true
        } catch {
            // TODO: backend may not be ready yet.
            errorMessage = error.localizedDescription
        }
    }

    func loadPlan() async {
        errorMessage = nil
        isLoading = true
        defer { isLoading = false }

        do {
            let payload = try await OpenClawAPI.shared.getPlan()
            let tier = PlanTier(rawValue: payload.tier) ?? plan.tier

            // Best-effort mapping; backend fields may change during sync.
            let limit = payload.limits?.messagesPerDay ?? plan.dailyLimit
            let used = payload.usage?.messagesToday ?? plan.usedToday

            plan = PlanInfo(tier: tier, dailyLimit: limit, usedToday: used)
        } catch {
            // TODO: backend may not be ready yet; keep mock.
            errorMessage = error.localizedDescription
        }
    }

    func loadAIConfig() async {
        errorMessage = nil
        isLoading = true
        defer { isLoading = false }

        do {
            let payload = try await OpenClawAPI.shared.getAIConfig()
            let persona = Persona(rawValue: payload.persona) ?? aiConfig.persona
            let temp = payload.temperature ?? aiConfig.temperature
            let prompt = payload.customPrompt ?? aiConfig.customPrompt
            aiConfig = AIConfig(persona: persona, customPrompt: prompt, temperature: temp)
        } catch {
            // TODO: backend may not be ready yet; keep mock.
            errorMessage = error.localizedDescription
        }
    }

    func updateAIConfig(persona: Persona, customPrompt: String?, temperature: Double?) async {
        errorMessage = nil
        isLoading = true
        defer { isLoading = false }

        let previous = aiConfig
        aiConfig.persona = persona
        if let t = temperature { aiConfig.temperature = t }
        if let p = customPrompt { aiConfig.customPrompt = p }

        do {
            let payload = try await OpenClawAPI.shared.updateAIConfig(
                persona: persona.rawValue,
                customPrompt: customPrompt,
                temperature: temperature
            )
            let nextPersona = Persona(rawValue: payload.persona) ?? persona
            aiConfig = AIConfig(
                persona: nextPersona,
                customPrompt: payload.customPrompt ?? (customPrompt ?? ""),
                temperature: payload.temperature ?? (temperature ?? aiConfig.temperature)
            )
            AnalyticsService.shared.track(
                "settings_changed",
                properties: [
                    "setting": "ai_config",
                    "persona": persona.rawValue
                ]
            )
        } catch {
            // TODO: backend may not be ready yet; keep local update.
            aiConfig = previous
            errorMessage = error.localizedDescription
        }
    }

    func clearAllConversations() async {
        errorMessage = nil
        isLoading = true
        defer { isLoading = false }

        do {
            // Fetch *all* conversations via pagination, then delete one-by-one.
            let pageSize = 200
            var offset = 0
            var all: [ConversationSummary] = []
            var seenIds: Set<String> = []

            while true {
                let page = try await OpenClawAPI.shared.listConversations(limit: pageSize, offset: offset)
                if page.isEmpty { break }

                // Defensive de-dupe to avoid infinite loops if backend pagination is unstable.
                let newOnes = page.filter { seenIds.insert($0.id).inserted }
                if newOnes.isEmpty { break }

                all.append(contentsOf: newOnes)

                if page.count < pageSize { break }
                offset += pageSize
            }

            for convo in all {
                _ = try await OpenClawAPI.shared.deleteConversation(id: convo.id)
            }

            NotificationCenter.default.post(name: .clawPhonesConversationsDidChange, object: nil)
        } catch {
            // TODO: backend may not be ready yet.
            errorMessage = error.localizedDescription
        }
    }

    func deleteAccount() async -> Bool {
        errorMessage = nil
        isLoading = true
        defer { isLoading = false }

        do {
            try await OpenClawAPI.shared.deleteAccount(confirm: true)
            await purgeLocalUserDataAfterAccountDeletion()
            return true
        } catch {
            errorMessage = error.localizedDescription
            return false
        }
    }

    func exportMyData() async -> URL? {
        if isExportingData {
            return nil
        }

        errorMessage = nil
        isLoading = true
        isExportingData = true
        defer {
            isExportingData = false
            isLoading = false
        }

        do {
            return try await OpenClawAPI.shared.exportUserDataToTemporaryFile()
        } catch {
            errorMessage = error.localizedDescription
            return nil
        }
    }

    func logout() {
        // Ensure all auth state is cleared.
        DeviceConfig.shared.deviceToken = nil

        // Best-effort: clear any persisted login keys in UserDefaults.
        UserDefaults.standard.removeObject(forKey: DeviceConfig.managedDeviceTokenKey)
        UserDefaults.standard.removeObject(forKey: DeviceConfig.managedBaseURLKey)
        UserDefaults.standard.removeObject(forKey: DeviceConfig.managedModeKey)

        NotificationCenter.default.post(name: .clawPhonesAuthDidChange, object: nil)
    }

    private func purgeLocalUserDataAfterAccountDeletion() async {
        DeviceConfig.shared.clearTokens()
        UserDefaults.standard.removeObject(forKey: DeviceConfig.managedDeviceTokenKey)
        UserDefaults.standard.removeObject(forKey: DeviceConfig.managedTokenExpiresAtKey)
        UserDefaults.standard.removeObject(forKey: DeviceConfig.managedBaseURLKey)
        UserDefaults.standard.removeObject(forKey: DeviceConfig.managedModeKey)

        await ConversationCache.shared.clearAll()
        MessageQueue.shared.clearAll()

        profile = .mock
        plan = .mock
        aiConfig = .mock

        NotificationCenter.default.post(name: .clawPhonesConversationsDidChange, object: nil)
        NotificationCenter.default.post(name: .clawPhonesAuthDidChange, object: nil)
    }
}

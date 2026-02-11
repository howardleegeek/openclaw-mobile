//
//  VoiceResponder.swift
//  ClawPhones
//

import AVFoundation
import Foundation

final class VoiceResponder {
    // AVSpeechSynthesizer TTS
    private let synthesizer = AVSpeechSynthesizer()
    private var lastResponseAt: Date?

    // 预设语音响应
    enum ResponseType: String, CaseIterable, Identifiable, Codable {
        case deterrent
        case welcome
        case recording
        case custom

        var id: String { rawValue }

        var displayName: String {
            switch self {
            case .deterrent:
                return "威慑提醒"
            case .welcome:
                return "欢迎语"
            case .recording:
                return "监控提示"
            case .custom:
                return "自定义"
            }
        }

        var defaultMessage: String {
            switch self {
            case .deterrent:
                return "已录像，请勿靠近"
            case .welcome:
                return "欢迎回家"
            case .recording:
                return "此区域正在监控中"
            case .custom:
                return ""
            }
        }
    }

    // 触发条件
    struct TriggerConfig: Codable, Equatable {
        static let enabledKey = "ai.clawphones.node.voice.enabled"
        static let responseTypeKey = "ai.clawphones.node.voice.response_type"
        static let customMessageKey = "ai.clawphones.node.voice.custom_message"
        static let onlyForPersonKey = "ai.clawphones.node.voice.only_person"
        static let cooldownKey = "ai.clawphones.node.voice.cooldown"

        var enabled: Bool = false
        var responseType: ResponseType = .recording
        var customMessage: String = ""
        var onlyForPerson: Bool = true  // 只对人触发，不对动物
        var cooldown: TimeInterval = 60  // 60秒内不重复

        func resolvedMessage() -> String {
            if responseType == .custom {
                let trimmed = customMessage.trimmingCharacters(in: .whitespacesAndNewlines)
                return trimmed.isEmpty ? ResponseType.recording.defaultMessage : trimmed
            }
            return responseType.defaultMessage
        }

        static func loadFromDefaults(_ defaults: UserDefaults = .standard) -> TriggerConfig {
            var config = TriggerConfig()
            config.enabled = defaults.object(forKey: enabledKey) as? Bool ?? config.enabled
            if let raw = defaults.string(forKey: responseTypeKey),
               let type = ResponseType(rawValue: raw) {
                config.responseType = type
            }
            config.customMessage = defaults.string(forKey: customMessageKey) ?? ""
            config.onlyForPerson = defaults.object(forKey: onlyForPersonKey) as? Bool ?? config.onlyForPerson
            config.cooldown = defaults.object(forKey: cooldownKey) as? TimeInterval ?? config.cooldown
            config.cooldown = max(1, config.cooldown)
            return config
        }

        func saveToDefaults(_ defaults: UserDefaults = .standard) {
            defaults.set(enabled, forKey: Self.enabledKey)
            defaults.set(responseType.rawValue, forKey: Self.responseTypeKey)
            defaults.set(customMessage, forKey: Self.customMessageKey)
            defaults.set(onlyForPerson, forKey: Self.onlyForPersonKey)
            defaults.set(max(1, cooldown), forKey: Self.cooldownKey)
        }
    }

    func respond(to detection: VisionDetector.Detection, config: TriggerConfig) {
        guard config.enabled else { return }
        if config.onlyForPerson && detection.type != .person {
            return
        }

        let now = Date()
        let cooldown = max(1, config.cooldown)
        if let lastResponseAt, now.timeIntervalSince(lastResponseAt) < cooldown {
            return
        }

        let message = config.resolvedMessage()
        guard !message.isEmpty else { return }
        guard configureAudioSession() else { return }

        let utterance = AVSpeechUtterance(string: message)
        utterance.voice = bestVoice(for: message)
        // 音量默认跟随系统，不额外覆写 utterance.volume
        utterance.rate = AVSpeechUtteranceDefaultSpeechRate

        synthesizer.speak(utterance)
        lastResponseAt = now
    }

    private func configureAudioSession() -> Bool {
        let session = AVAudioSession.sharedInstance()
        do {
            // 后台播放: AVAudioSession.Category.playback
            try session.setCategory(.playback, mode: .spokenAudio, options: [.duckOthers])
            try session.setActive(true, options: .notifyOthersOnDeactivation)
            return true
        } catch {
            return false
        }
    }

    private func bestVoice(for message: String) -> AVSpeechSynthesisVoice? {
        let preferred = preferredLanguage(for: message)
        if let exact = AVSpeechSynthesisVoice(language: preferred) {
            return exact
        }

        if preferred == "zh-CN" {
            return AVSpeechSynthesisVoice(language: "en-US")
        }
        return AVSpeechSynthesisVoice(language: "zh-CN")
    }

    private func preferredLanguage(for message: String) -> String {
        let containsChinese = message.range(of: "\\p{Han}", options: .regularExpression) != nil
        return containsChinese ? "zh-CN" : "en-US"
    }
}

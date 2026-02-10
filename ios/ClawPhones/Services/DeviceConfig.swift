//
//  DeviceConfig.swift
//  ClawPhones
//
//  Device token management (MDM + Keychain fallback)
//

import Foundation

class DeviceConfig {
    static let shared = DeviceConfig()
    private init() {}

    private let mdmKey = "ai.openclaw.device_token"
    private let mdmBaseURLKey = "ai.openclaw.base_url"
    private let mdmModeKey = "ai.openclaw.mode"

    /// Get device token (priority: MDM → Keychain → nil)
    var deviceToken: String? {
        // 1. MDM Managed App Configuration (highest priority)
        if let mdmToken = UserDefaults.standard.string(forKey: mdmKey),
           !mdmToken.isEmpty {
            return mdmToken
        }

        // 2. Keychain (factory pre-installed)
        if let keychainToken = KeychainHelper.shared.readDeviceToken(),
           !keychainToken.isEmpty {
            return keychainToken
        }

        // 3. No token available
        return nil
    }

    /// OpenClaw API base URL
    var baseURL: String {
        // Allow MDM override (for testing/staging)
        if let mdmURL = UserDefaults.standard.string(forKey: mdmBaseURLKey),
           !mdmURL.isEmpty {
            return mdmURL
        }

        return "https://api.openclaw.ai"
    }

    /// Device mode/tier
    var mode: Mode {
        if let modeString = UserDefaults.standard.string(forKey: mdmModeKey) {
            return Mode(rawValue: modeString) ?? .auto
        }
        return .auto
    }

    enum Mode: String {
        case auto
        case deepseek
        case kimi
        case claude
    }

    /// Check if device is provisioned
    var isProvisioned: Bool {
        return deviceToken != nil
    }

    /// Save user-authenticated token (fallback for non-Oyster devices)
    func saveUserToken(_ token: String) {
        _ = KeychainHelper.shared.writeDeviceToken(token)
    }

    /// Clear all stored tokens (for testing)
    func clearTokens() {
        KeychainHelper.shared.clearAll()
        UserDefaults.standard.removeObject(forKey: mdmKey)
    }
}

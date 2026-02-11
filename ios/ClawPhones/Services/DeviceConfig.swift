//
//  DeviceConfig.swift
//  ClawPhones
//
//  Device token management (Keychain only for token persistence)
//

import Foundation

class DeviceConfig {
    static let shared = DeviceConfig()
    private init() {
        migrateLegacyTokenIfNeeded()
    }

    // Managed App Configuration (MDM) keys (also used for local overrides in dev builds)
    static let managedDeviceTokenKey = "ai.openclaw.device_token"
    static let managedTokenExpiresAtKey = "ai.openclaw.token_expires_at"
    static let managedBaseURLKey = "ai.openclaw.base_url"
    static let managedModeKey = "ai.openclaw.mode"
    static let legacyAuthTokenKey = "auth_token"

    /// Get/set device token.
    /// Priority (read): Keychain → nil
    /// Behavior (write): non-empty → Keychain, nil/empty → clear all stored tokens.
    var deviceToken: String? {
        get {
            if let keychainToken = KeychainHelper.shared.readDeviceToken(),
               !keychainToken.isEmpty {
                return keychainToken
            }
            return nil
        }
        set {
            let trimmed = newValue?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            if trimmed.isEmpty {
                clearTokens()
            } else {
                saveUserToken(trimmed)
            }
        }
    }

    /// OpenClaw API base URL
    var baseURL: String {
        // Allow MDM override (for testing/staging)
        if let mdmURL = UserDefaults.standard.string(forKey: Self.managedBaseURLKey),
           !mdmURL.isEmpty,
           mdmURL.lowercased().hasPrefix("https://") {
            return mdmURL
        }

        return "https://3.142.69.6:8080"
    }

    /// Device mode/tier
    var mode: Mode {
        if let modeString = UserDefaults.standard.string(forKey: Self.managedModeKey) {
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
        guard deviceToken != nil else { return false }
        if let expiresAt = tokenExpiresAt,
           expiresAt > 0,
           Int(Date().timeIntervalSince1970) >= expiresAt {
            clearTokens()
            return false
        }
        return true
    }

    /// Save user-authenticated token (fallback for non-Oyster devices)
    func saveUserToken(_ token: String, expiresAt: Int? = nil) {
        _ = KeychainHelper.shared.writeDeviceToken(token)
        if let expiresAt, expiresAt > 0 {
            UserDefaults.standard.set(expiresAt, forKey: Self.managedTokenExpiresAtKey)
        } else {
            UserDefaults.standard.removeObject(forKey: Self.managedTokenExpiresAtKey)
        }
    }

    var tokenExpiresAt: Int? {
        get {
            let value = UserDefaults.standard.object(forKey: Self.managedTokenExpiresAtKey)
            if let intValue = value as? Int, intValue > 0 {
                return intValue
            }
            if let numberValue = value as? NSNumber {
                let intValue = numberValue.intValue
                return intValue > 0 ? intValue : nil
            }
            return nil
        }
        set {
            if let newValue, newValue > 0 {
                UserDefaults.standard.set(newValue, forKey: Self.managedTokenExpiresAtKey)
            } else {
                UserDefaults.standard.removeObject(forKey: Self.managedTokenExpiresAtKey)
            }
        }
    }

    /// Clear all stored tokens (for testing)
    func clearTokens() {
        KeychainHelper.shared.clearAll()
        UserDefaults.standard.removeObject(forKey: Self.managedDeviceTokenKey)
        UserDefaults.standard.removeObject(forKey: Self.legacyAuthTokenKey)
        UserDefaults.standard.removeObject(forKey: Self.managedTokenExpiresAtKey)
    }

    private func migrateLegacyTokenIfNeeded() {
        let defaults = UserDefaults.standard
        let keychainToken = KeychainHelper.shared.readDeviceToken()?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""

        var legacyToken = defaults.string(forKey: Self.managedDeviceTokenKey)?.trimmingCharacters(in: .whitespacesAndNewlines)
        if legacyToken?.isEmpty ?? true {
            legacyToken = defaults.string(forKey: Self.legacyAuthTokenKey)?.trimmingCharacters(in: .whitespacesAndNewlines)
        }

        if keychainToken.isEmpty, let token = legacyToken, !token.isEmpty {
            _ = KeychainHelper.shared.writeDeviceToken(token)
        }

        defaults.removeObject(forKey: Self.managedDeviceTokenKey)
        defaults.removeObject(forKey: Self.legacyAuthTokenKey)
    }
}

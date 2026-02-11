//
//  BiometricAuthService.swift
//  ClawPhones
//

import Foundation
import LocalAuthentication

@MainActor
final class BiometricAuthService {
    static let shared = BiometricAuthService()
    static let lockEnabledStorageKey = "biometric_lock_enabled"

    struct BiometryInfo {
        let isAvailable: Bool
        let type: LABiometryType

        var displayName: String {
            switch type {
            case .faceID:
                return "Face ID"
            case .touchID:
                return "Touch ID"
            default:
                return "生物识别"
            }
        }
    }

    private init() {}

    func currentBiometryInfo() -> BiometryInfo {
        let context = LAContext()
        context.localizedCancelTitle = "取消"

        var error: NSError?
        let canEvaluate = context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error)
        let type = canEvaluate ? context.biometryType : .none

        return BiometryInfo(isAvailable: canEvaluate, type: type)
    }

    func authenticateForAppUnlock() async -> Bool {
        let info = currentBiometryInfo()
        guard info.isAvailable else { return false }
        return await authenticate(
            reason: "使用\(info.displayName)解锁 ClawPhones",
            allowPasscodeFallback: true
        )
    }

    func authenticate(reason: String, allowPasscodeFallback: Bool = false) async -> Bool {
        let context = LAContext()
        context.localizedCancelTitle = "取消"
        context.localizedFallbackTitle = allowPasscodeFallback ? "使用密码" : ""

        let policy: LAPolicy = allowPasscodeFallback
            ? .deviceOwnerAuthentication
            : .deviceOwnerAuthenticationWithBiometrics

        var error: NSError?
        guard context.canEvaluatePolicy(policy, error: &error) else {
            return false
        }

        do {
            return try await context.evaluatePolicy(
                policy,
                localizedReason: reason
            )
        } catch {
            return false
        }
    }
}

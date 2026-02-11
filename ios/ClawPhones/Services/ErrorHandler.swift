//
//  ErrorHandler.swift
//  ClawPhones
//

import Foundation
import SwiftUI

@MainActor
final class ErrorHandler: ObservableObject {
    static let shared = ErrorHandler()

    struct BannerState: Identifiable {
        let id = UUID()
        let message: String
    }

    struct AlertState: Identifiable {
        let id = UUID()
        let title: String
        let message: String
        let retryTitle: String?
        let retryAction: (() -> Void)?
    }

    @Published var banner: BannerState?
    @Published var alert: AlertState?

    private var bannerDismissTask: Task<Void, Never>?
    private var rateLimitCountdownTask: Task<Void, Never>?

    private init() {}

    func clearAlert() {
        alert = nil
    }

    func handle(_ error: Error, retryAction: (() -> Void)? = nil) {
        switch classify(error) {
        case .noNetwork:
            showBanner("无网络连接", autoDismissAfter: 3.0)
        case .unauthorized:
            DeviceConfig.shared.clearTokens()
            NotificationCenter.default.post(name: Notification.Name("ClawPhonesAuthExpired"), object: nil)
            showBanner("登录已过期，请重新登录", autoDismissAfter: 2.0)
        case .rateLimited(let seconds):
            showRateLimitCountdown(seconds: seconds)
        case .serverUnavailable:
            showBanner("服务暂时不可用", autoDismissAfter: 3.0)
        case .timeout:
            showAlert(
                title: "请求超时",
                message: "请求超时，点击重试",
                retryTitle: retryAction == nil ? nil : "重试",
                retryAction: retryAction
            )
        case .generic(let message):
            showAlert(
                title: "错误",
                message: message,
                retryTitle: retryAction == nil ? nil : "重试",
                retryAction: retryAction
            )
        }
    }

    private enum Classified {
        case noNetwork
        case unauthorized
        case rateLimited(Int)
        case serverUnavailable
        case timeout
        case generic(String)
    }

    private func classify(_ error: Error) -> Classified {
        if let clawError = error as? ClawPhonesError {
            switch clawError {
            case .unauthorized:
                return .unauthorized
            case .networkError(let wrapped):
                if let urlError = wrapped as? URLError {
                    return classify(urlError)
                }
                let ns = wrapped as NSError
                if ns.domain == NSURLErrorDomain {
                    return classify(URLError.Code(rawValue: ns.code))
                }
                if isTimeoutMessage(wrapped.localizedDescription) {
                    return .timeout
                }
                return .generic(cleanMessage(wrapped.localizedDescription))
            case .apiError(let message):
                if isTimeoutMessage(message) {
                    return .timeout
                }
                return .generic(cleanMessage(message))
            case .noDeviceToken:
                return .unauthorized
            case .decodingError:
                return .generic("响应解析失败，请稍后重试")
            }
        }

        if let urlError = error as? URLError {
            return classify(urlError)
        }

        let nsError = error as NSError
        if nsError.domain == NSURLErrorDomain {
            return classify(URLError.Code(rawValue: nsError.code))
        }
        if isTimeoutMessage(error.localizedDescription) {
            return .timeout
        }
        return .generic(cleanMessage(error.localizedDescription))
    }

    private func classify(_ urlError: URLError) -> Classified {
        classify(urlError.code)
    }

    private func classify(_ code: URLError.Code) -> Classified {
        switch code {
        case .notConnectedToInternet, .networkConnectionLost, .cannotFindHost, .cannotConnectToHost, .dnsLookupFailed:
            return .noNetwork
        case .timedOut:
            return .timeout
        default:
            return .generic("请求失败，请稍后重试")
        }
    }

    private func showAlert(title: String, message: String, retryTitle: String?, retryAction: (() -> Void)?) {
        cancelBannerTasks()
        alert = AlertState(
            title: title,
            message: message,
            retryTitle: retryTitle,
            retryAction: retryAction
        )
    }

    private func showBanner(_ message: String, autoDismissAfter seconds: TimeInterval?) {
        rateLimitCountdownTask?.cancel()
        rateLimitCountdownTask = nil

        bannerDismissTask?.cancel()
        bannerDismissTask = nil

        banner = BannerState(message: message)
        guard let seconds else { return }

        bannerDismissTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(max(0.1, seconds) * 1_000_000_000))
            guard !Task.isCancelled else { return }
            await MainActor.run {
                self?.banner = nil
            }
        }
    }

    private func showRateLimitCountdown(seconds: Int) {
        bannerDismissTask?.cancel()
        bannerDismissTask = nil

        rateLimitCountdownTask?.cancel()
        rateLimitCountdownTask = Task { [weak self] in
            guard let self else { return }
            var remaining = max(1, seconds)
            while remaining > 0 {
                await MainActor.run {
                    self.banner = BannerState(message: "请稍后再试（\(remaining)s）")
                }
                if remaining == 1 { break }
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                guard !Task.isCancelled else { return }
                remaining -= 1
            }
            await MainActor.run {
                self.banner = BannerState(message: "请稍后再试")
            }
            try? await Task.sleep(nanoseconds: 1_000_000_000)
            guard !Task.isCancelled else { return }
            await MainActor.run {
                self.banner = nil
            }
        }
    }

    private func cancelBannerTasks() {
        bannerDismissTask?.cancel()
        bannerDismissTask = nil
        rateLimitCountdownTask?.cancel()
        rateLimitCountdownTask = nil
    }

    private func isTimeoutMessage(_ text: String) -> Bool {
        let lower = text.lowercased()
        return lower.contains("timeout") || lower.contains("timed out")
    }

    private func extractRetryAfter(from message: String) -> Int? {
        guard !message.isEmpty else { return nil }
        let pattern = #"(?:retry_after|Retry-After)\D+(\d+)"#
        if let regex = try? NSRegularExpression(pattern: pattern),
           let match = regex.firstMatch(in: message, range: NSRange(message.startIndex..., in: message)),
           let range = Range(match.range(at: 1), in: message),
           let value = Int(message[range]),
           value > 0 {
            return value
        }

        if let firstNumber = message.split(whereSeparator: { !$0.isNumber }).first,
           let value = Int(firstNumber),
           value > 0 {
            return value
        }
        return nil
    }

    private func cleanMessage(_ message: String) -> String {
        let trimmed = message.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            return "请求失败，请稍后重试"
        }
        return trimmed
    }
}

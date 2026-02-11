//
//  CrashReporter.swift
//  ClawPhones
//

import Foundation
import UIKit

final class CrashReporter {
    static let shared = CrashReporter()

    private let fileManager = FileManager.default
    private let stateQueue = DispatchQueue(label: "ai.clawphones.crashreporter.state")
    private var lastAction: String = ""
    private var recentErrorTimestamps: [String: Int] = [:]

    private init() {}

    // crash_logs directory under Documents
    private var crashLogDir: URL {
        let docs = fileManager.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let dir = docs.appendingPathComponent("crash_logs")
        if !fileManager.fileExists(atPath: dir.path) {
            try? fileManager.createDirectory(at: dir, withIntermediateDirectories: true)
        }
        return dir
    }

    func setLastAction(_ action: String) {
        let trimmed = action.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        stateQueue.sync {
            lastAction = trimmed
        }
    }

    func reportNonFatal(error: Error, action: String) {
        let now = Int(Date().timeIntervalSince1970)
        let description = error.localizedDescription
        let dedupeKey = stableHash(for: description)

        var resolvedAction = action.trimmingCharacters(in: .whitespacesAndNewlines)
        var shouldSave = false

        stateQueue.sync {
            if resolvedAction.isEmpty {
                resolvedAction = lastAction.isEmpty ? "unknown" : lastAction
            }

            recentErrorTimestamps = recentErrorTimestamps.filter { now - $0.value < 300 }
            if let lastTimestamp = recentErrorTimestamps[dedupeKey], now - lastTimestamp < 300 {
                shouldSave = false
            } else {
                recentErrorTimestamps[dedupeKey] = now
                shouldSave = true
            }
        }

        guard shouldSave else { return }

        let stacktrace = String(String(describing: error).prefix(5000))
        let report: [String: Any] = [
            "platform": "ios",
            "app_version": Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "unknown",
            "device_model": UIDevice.current.model,
            "os_version": "iOS \(UIDevice.current.systemVersion)",
            "stacktrace": stacktrace,
            "user_action": resolvedAction,
            "fatal": false,
            "timestamp": now
        ]

        saveToFile(report)
    }

    func uploadPendingReports() {
        guard let token = authToken() else { return }

        let files = (try? fileManager.contentsOfDirectory(
            at: crashLogDir,
            includingPropertiesForKeys: nil,
            options: [.skipsHiddenFiles]
        )) ?? []

        for file in files where file.pathExtension == "json" {
            Task(priority: .utility) {
                guard let data = try? Data(contentsOf: file) else { return }

                do {
                    var request = URLRequest(url: URL(string: "\(OpenClawAPI.shared.baseURL)/v1/crash-reports")!)
                    request.httpMethod = "POST"
                    request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
                    request.setValue("application/json", forHTTPHeaderField: "Content-Type")
                    request.httpBody = data

                    let (_, response) = try await URLSession.shared.data(for: request)
                    if let http = response as? HTTPURLResponse, http.statusCode == 200 {
                        try? fileManager.removeItem(at: file)
                    }
                } catch {
                    // Keep file for retry on next app launch.
                }
            }
        }
    }

    private func saveToFile(_ report: [String: Any]) {
        let filename = "\(Int(Date().timeIntervalSince1970)).json"
        let fileURL = crashLogDir.appendingPathComponent(filename)

        if let data = try? JSONSerialization.data(withJSONObject: report) {
            try? data.write(to: fileURL)
        }

        cleanOldFiles()
    }

    private func cleanOldFiles() {
        let files = (try? fileManager.contentsOfDirectory(
            at: crashLogDir,
            includingPropertiesForKeys: [.creationDateKey, .contentModificationDateKey],
            options: [.skipsHiddenFiles]
        )) ?? []

        let jsonFiles = files.filter { $0.pathExtension == "json" }.sorted { lhs, rhs in
            fileDate(for: lhs) < fileDate(for: rhs)
        }

        let overflow = jsonFiles.count - 50
        guard overflow > 0 else { return }

        for file in jsonFiles.prefix(overflow) {
            try? fileManager.removeItem(at: file)
        }
    }

    private func fileDate(for url: URL) -> Date {
        let values = try? url.resourceValues(forKeys: [.creationDateKey, .contentModificationDateKey])
        return values?.creationDate ?? values?.contentModificationDate ?? Date.distantPast
    }

    private func authToken() -> String? {
        if let token = KeychainHelper.shared.readDeviceToken()?.trimmingCharacters(in: .whitespacesAndNewlines),
           !token.isEmpty {
            return token
        }

        if let token = KeychainHelper.shared.read(key: "auth_token")?.trimmingCharacters(in: .whitespacesAndNewlines),
           !token.isEmpty {
            return token
        }

        return nil
    }

    private func stableHash(for value: String) -> String {
        var hash: UInt64 = 1469598103934665603
        let prime: UInt64 = 1099511628211

        for byte in value.utf8 {
            hash ^= UInt64(byte)
            hash = hash &* prime
        }

        return String(hash, radix: 16)
    }
}

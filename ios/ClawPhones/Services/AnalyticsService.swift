//
//  AnalyticsService.swift
//  ClawPhones
//

import Foundation
import UIKit

final class AnalyticsService {
    static let shared = AnalyticsService()

    private let workerQueue = DispatchQueue(label: "ai.clawphones.analytics", qos: .utility)
    private let flushInterval: TimeInterval = 30
    private var pendingEvents: [[String: Any]] = []
    private var isFlushing = false
    private var flushTimer: DispatchSourceTimer?

    private init() {
        startFlushTimer()
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleDidEnterBackground),
            name: UIApplication.didEnterBackgroundNotification,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleWillTerminate),
            name: UIApplication.willTerminateNotification,
            object: nil
        )
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
        flushTimer?.cancel()
    }

    func track(_ eventName: String, properties: [String: Any] = [:]) {
        let normalizedEvent = eventName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalizedEvent.isEmpty else { return }

        let event: [String: Any] = [
            "event": normalizedEvent,
            "properties": sanitizeDictionary(properties),
            "timestamp": Int(Date().timeIntervalSince1970)
        ]

        workerQueue.async { [weak self] in
            guard let self else { return }
            self.pendingEvents.append(event)
        }
    }

    func flushNow() {
        workerQueue.async { [weak self] in
            self?.flushLocked()
        }
    }

    @objc
    private func handleDidEnterBackground() {
        flushNow()
    }

    @objc
    private func handleWillTerminate() {
        flushNow()
    }

    private func startFlushTimer() {
        workerQueue.async { [weak self] in
            guard let self else { return }

            let timer = DispatchSource.makeTimerSource(queue: self.workerQueue)
            timer.schedule(
                deadline: .now() + self.flushInterval,
                repeating: self.flushInterval
            )
            timer.setEventHandler { [weak self] in
                self?.flushLocked()
            }
            self.flushTimer = timer
            timer.resume()
        }
    }

    private func flushLocked() {
        guard !isFlushing else { return }
        guard !pendingEvents.isEmpty else { return }

        let batch = pendingEvents
        pendingEvents.removeAll(keepingCapacity: true)
        isFlushing = true

        send(batch: batch) { [weak self] success in
            guard let self else { return }
            self.workerQueue.async {
                self.isFlushing = false
                if !success {
                    self.pendingEvents.insert(contentsOf: batch, at: 0)
                }
            }
        }
    }

    private func send(batch: [[String: Any]], completion: @escaping (Bool) -> Void) {
        guard let url = URL(string: "\(OpenClawAPI.shared.baseURL)/v1/analytics/events") else {
            completion(false)
            return
        }

        Task.detached(priority: .utility) {
            do {
                let payload = try JSONSerialization.data(withJSONObject: batch, options: [])
                var request = URLRequest(url: url)
                request.httpMethod = "POST"
                request.timeoutInterval = 10
                request.setValue("application/json", forHTTPHeaderField: "Accept")
                request.setValue("application/json", forHTTPHeaderField: "Content-Type")

                if let token = DeviceConfig.shared.deviceToken?.trimmingCharacters(in: .whitespacesAndNewlines),
                   !token.isEmpty {
                    request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
                }

                request.httpBody = payload

                let (_, response) = try await URLSession.shared.data(for: request)
                guard let httpResponse = response as? HTTPURLResponse else {
                    completion(false)
                    return
                }

                completion((200...299).contains(httpResponse.statusCode))
            } catch {
                completion(false)
            }
        }
    }

    private func sanitizeDictionary(_ value: [String: Any]) -> [String: Any] {
        var out: [String: Any] = [:]
        for (key, rawValue) in value {
            let normalizedKey = key.trimmingCharacters(in: .whitespacesAndNewlines)
            if normalizedKey.isEmpty { continue }
            out[normalizedKey] = sanitizeValue(rawValue)
        }
        return out
    }

    private func sanitizeValue(_ value: Any) -> Any {
        switch value {
        case let v as String:
            return v
        case let v as Int:
            return v
        case let v as Int64:
            return v
        case let v as Double:
            return v
        case let v as Float:
            return Double(v)
        case let v as Bool:
            return v
        case let v as NSNumber:
            return v
        case let v as [String: Any]:
            return sanitizeDictionary(v)
        case let v as [Any]:
            return v.map { sanitizeValue($0) }
        case _ as NSNull:
            return NSNull()
        default:
            return String(describing: value)
        }
    }
}

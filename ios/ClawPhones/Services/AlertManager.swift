//
//  AlertManager.swift
//  ClawPhones
//

import Foundation
import UIKit
import CoreLocation
import UserNotifications
import UniformTypeIdentifiers

final class AlertManager: NSObject {
    static let shared = AlertManager()
    static let openNodeModeNotification = Notification.Name("ClawVisionOpenNodeMode")

    struct AlertEvent: Codable {
        let type: String        // person, vehicle, animal, package
        let confidence: Float
        let timestamp: Date
        let thumbnail: Data?    // JPEG thumbnail
    }

    private enum AlertType: Int {
        case package = 1
        case animal = 2
        case vehicle = 3
        case person = 4

        static func from(rawType: String) -> AlertType? {
            switch rawType.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
            case "person":
                return .person
            case "vehicle":
                return .vehicle
            case "animal":
                return .animal
            case "package":
                return .package
            default:
                return nil
            }
        }

        static func from(detectionType: VisionDetector.DetectionType) -> AlertType? {
            from(rawType: detectionType.rawValue)
        }

        var zhLabel: String {
            switch self {
            case .person:
                return "人"
            case .vehicle:
                return "车"
            case .animal:
                return "动物"
            case .package:
                return "包裹"
            }
        }
    }

    private struct QuietHoursWindow: Codable {
        let startMinuteOfDay: Int
        let endMinuteOfDay: Int
    }

    private let notificationCenter = UNUserNotificationCenter.current()
    private let workerQueue = DispatchQueue(label: "ai.clawphones.alert-manager", qos: .utility)

    private let historyStorageKey = "clawvision.alert_history.json"
    private let quietHoursStorageKey = "clawvision.alert_quiet_hours.json"
    private let minAlertRankStorageKey = "clawvision.alert_min_rank"
    private let minConfidenceStorageKey = "clawvision.alert_min_confidence"
    private let pendingNodeModeOpenKey = "clawvision.alert_pending_node_mode_open"
    private let lastSentPrefix = "clawvision.alert_last_sent."

    private let debounceWindow: TimeInterval = 30
    private let maxHistoryCount = 100
    private let nodeModeTarget = "node_mode"

    private static let timeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = "HH:mm"
        return formatter
    }()

    private override init() {
        super.init()
        notificationCenter.delegate = self
        requestNotificationAuthorizationIfNeeded()
    }

    static func isNodeModeURL(_ url: URL) -> Bool {
        url.scheme == "clawphones" && url.host == "node-mode"
    }

    static func displayType(_ type: String) -> String {
        guard let mapped = AlertType.from(rawType: type) else { return type }
        return mapped.zhLabel
    }

    // 防抖: 同类事件 30 秒内只推送一次
    // 静默时段: 用户可设置免打扰时间段
    // 重要度过滤: person > vehicle > animal > package
    func processDetection(
        _ detection: VisionDetector.Detection,
        frame: UIImage,
        location: CLLocation? = nil
    ) {
        guard let alertType = AlertType.from(detectionType: detection.type) else { return }
        let timestamp = Date()

        workerQueue.async { [weak self] in
            guard let self else { return }

            if self.isDebounced(type: alertType, timestamp: timestamp) {
                return
            }

            if self.isInQuietHours(timestamp) {
                return
            }

            let minimumRank = self.minimumAllowedRank()
            guard alertType.rawValue >= minimumRank else {
                return
            }

            let minimumConfidence = self.minimumConfidence()
            guard detection.confidence >= minimumConfidence else {
                return
            }

            let event = AlertEvent(
                type: detection.type.rawValue,
                confidence: detection.confidence,
                timestamp: timestamp,
                thumbnail: self.makeThumbnailData(from: frame)
            )

            self.storeHistory(event)
            Task {
                await AlertEventStore.shared.addEvent(
                    type: detection.type.rawValue,
                    confidence: detection.confidence,
                    timestamp: timestamp,
                    latitude: location?.coordinate.latitude ?? 0,
                    longitude: location?.coordinate.longitude ?? 0,
                    thumbnailData: event.thumbnail,
                    boundingBox: detection.boundingBox
                )
            }
            self.markLastSent(type: alertType, timestamp: timestamp)

            DispatchQueue.main.async {
                self.sendLocalNotification(event: event)
            }
        }
    }

    func sendLocalNotification(event: AlertEvent) {
        guard let mappedType = AlertType.from(rawType: event.type) else { return }

        let content = UNMutableNotificationContent()
        content.title = "ClawVision"
        content.body = "检测到 \(mappedType.zhLabel) \(Self.timeFormatter.string(from: event.timestamp))"
        content.sound = .default
        content.userInfo = [
            "open_target": nodeModeTarget,
            "event_type": event.type
        ]

        if let thumbnail = event.thumbnail,
           let attachment = createNotificationAttachment(jpegData: thumbnail) {
            content.attachments = [attachment]
        }

        let request = UNNotificationRequest(
            identifier: "clawvision.alert.\(UUID().uuidString)",
            content: content,
            trigger: nil
        )
        notificationCenter.add(request) { _ in }
    }

    func setQuietHours(startHour: Int, startMinute: Int = 0, endHour: Int, endMinute: Int = 0) {
        let start = max(0, min(23, startHour)) * 60 + max(0, min(59, startMinute))
        let end = max(0, min(23, endHour)) * 60 + max(0, min(59, endMinute))
        let window = QuietHoursWindow(startMinuteOfDay: start, endMinuteOfDay: end)
        if let data = try? JSONEncoder().encode(window),
           let jsonString = String(data: data, encoding: .utf8) {
            UserDefaults.standard.set(jsonString, forKey: quietHoursStorageKey)
        }
    }

    func clearQuietHours() {
        UserDefaults.standard.removeObject(forKey: quietHoursStorageKey)
    }

    func setMinimumAlertType(_ type: String) {
        guard let mapped = AlertType.from(rawType: type) else { return }
        UserDefaults.standard.set(mapped.rawValue, forKey: minAlertRankStorageKey)
    }

    func setMinimumConfidence(_ confidence: Float) {
        let clamped = max(0, min(1, confidence))
        UserDefaults.standard.set(clamped, forKey: minConfidenceStorageKey)
    }

    func recentHistory() -> [AlertEvent] {
        workerQueue.sync {
            loadHistory()
        }
    }

    func consumePendingNodeModeOpenRequest() -> Bool {
        let pending = UserDefaults.standard.bool(forKey: pendingNodeModeOpenKey)
        if pending {
            UserDefaults.standard.set(false, forKey: pendingNodeModeOpenKey)
        }
        return pending
    }

    private func requestNotificationAuthorizationIfNeeded() {
        notificationCenter.getNotificationSettings { [weak self] settings in
            guard settings.authorizationStatus == .notDetermined else { return }
            self?.notificationCenter.requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
        }
    }

    private func minimumAllowedRank() -> Int {
        let rank = UserDefaults.standard.integer(forKey: minAlertRankStorageKey)
        return rank == 0 ? AlertType.package.rawValue : rank
    }

    private func minimumConfidence() -> Float {
        guard UserDefaults.standard.object(forKey: minConfidenceStorageKey) != nil else {
            return 0.60
        }
        return UserDefaults.standard.float(forKey: minConfidenceStorageKey)
    }

    private func isDebounced(type: AlertType, timestamp: Date) -> Bool {
        let key = lastSentPrefix + String(type.rawValue)
        guard let lastSent = UserDefaults.standard.object(forKey: key) as? Date else {
            return false
        }
        return timestamp.timeIntervalSince(lastSent) < debounceWindow
    }

    private func markLastSent(type: AlertType, timestamp: Date) {
        let key = lastSentPrefix + String(type.rawValue)
        UserDefaults.standard.set(timestamp, forKey: key)
    }

    private func isInQuietHours(_ timestamp: Date) -> Bool {
        guard let raw = UserDefaults.standard.string(forKey: quietHoursStorageKey),
              let data = raw.data(using: .utf8),
              let quietHours = try? JSONDecoder().decode(QuietHoursWindow.self, from: data) else {
            return false
        }

        let components = Calendar.current.dateComponents([.hour, .minute], from: timestamp)
        let minuteOfDay = (components.hour ?? 0) * 60 + (components.minute ?? 0)

        if quietHours.startMinuteOfDay == quietHours.endMinuteOfDay {
            return true
        }

        if quietHours.startMinuteOfDay < quietHours.endMinuteOfDay {
            return minuteOfDay >= quietHours.startMinuteOfDay
                && minuteOfDay < quietHours.endMinuteOfDay
        }

        return minuteOfDay >= quietHours.startMinuteOfDay
            || minuteOfDay < quietHours.endMinuteOfDay
    }

    private func makeThumbnailData(from image: UIImage) -> Data? {
        let maxSide: CGFloat = 320
        let sourceSize = image.size
        guard sourceSize.width > 0, sourceSize.height > 0 else { return nil }

        let scale = min(1, maxSide / max(sourceSize.width, sourceSize.height))
        let targetSize = CGSize(
            width: max(1, sourceSize.width * scale),
            height: max(1, sourceSize.height * scale)
        )

        let renderer = UIGraphicsImageRenderer(size: targetSize)
        let thumbnail = renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: targetSize))
        }
        return thumbnail.jpegData(compressionQuality: 0.72)
    }

    private func createNotificationAttachment(jpegData: Data) -> UNNotificationAttachment? {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("clawvision-alert-attachments", isDirectory: true)
        try? FileManager.default.createDirectory(
            at: directory,
            withIntermediateDirectories: true
        )

        let fileURL = directory.appendingPathComponent("\(UUID().uuidString).jpg")
        do {
            try jpegData.write(to: fileURL, options: .atomic)
            return try UNNotificationAttachment(
                identifier: fileURL.lastPathComponent,
                url: fileURL,
                options: [
                    UNNotificationAttachmentOptionsTypeHintKey: UTType.jpeg.identifier
                ]
            )
        } catch {
            return nil
        }
    }

    private func loadHistory() -> [AlertEvent] {
        guard let raw = UserDefaults.standard.string(forKey: historyStorageKey),
              let data = raw.data(using: .utf8),
              let decoded = try? JSONDecoder().decode([AlertEvent].self, from: data) else {
            return []
        }
        return decoded
    }

    private func storeHistory(_ event: AlertEvent) {
        var history = loadHistory()
        history.insert(event, at: 0)
        if history.count > maxHistoryCount {
            history.removeLast(history.count - maxHistoryCount)
        }

        guard let data = try? JSONEncoder().encode(history),
              let json = String(data: data, encoding: .utf8) else {
            return
        }
        UserDefaults.standard.set(json, forKey: historyStorageKey)
    }

    private func markPendingNodeModeOpen() {
        UserDefaults.standard.set(true, forKey: pendingNodeModeOpenKey)
        NotificationCenter.default.post(name: Self.openNodeModeNotification, object: nil)
    }
}

extension AlertManager: UNUserNotificationCenterDelegate {
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        return [.banner, .sound, .list]
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse
    ) async {
        let userInfo = response.notification.request.content.userInfo
        let target = userInfo["open_target"] as? String
        guard target == nodeModeTarget else { return }
        markPendingNodeModeOpen()
    }
}

//
//  PushNotificationService.swift
//  ClawPhones
//
//  Service for managing push notifications with multiple categories
//

import Foundation
import UIKit
import UserNotifications
import Combine

@MainActor
class PushNotificationService: NSObject, ObservableObject {

    // MARK: - Published Properties

    @Published var isPermissionGranted: Bool = false
    @Published var deviceToken: String?
    @Published var isEnabled: Bool = false

    // MARK: - Notification Categories

    enum NotificationCategory: String, CaseIterable {
        case communityAlert = "COMMUNITY_ALERT"
        case taskUpdate = "TASK_UPDATE"
        case edgeJob = "EDGE_JOB"
        case securityAlert = "SECURITY_ALERT"

        var identifier: String {
            return rawValue
        }

        var displayName: String {
            switch self {
            case .communityAlert: return "社区提醒"
            case .taskUpdate: return "任务更新"
            case .edgeJob: return "边缘计算任务"
            case .securityAlert: return "安全提醒"
            }
        }

        var actions: [UNNotificationAction] {
            switch self {
            case .communityAlert:
                return [
                    UNNotificationAction(
                        identifier: "VIEW_ALERT",
                        title: "查看详情",
                        options: .foreground
                    ),
                    UNNotificationAction(
                        identifier: "DISMISS_ALERT",
                        title: "忽略",
                        options: .destructive
                    )
                ]
            case .taskUpdate:
                return [
                    UNNotificationAction(
                        identifier: "VIEW_TASK",
                        title: "查看任务",
                        options: .foreground
                    ),
                    UNNotificationAction(
                        identifier: "ACCEPT_TASK",
                        title: "接受任务",
                        options: []
                    )
                ]
            case .edgeJob:
                return [
                    UNNotificationAction(
                        identifier: "VIEW_JOB",
                        title: "查看任务",
                        options: .foreground
                    ),
                    UNNotificationAction(
                        identifier: "START_COMPUTE",
                        title: "开始计算",
                        options: []
                    ),
                    UNNotificationAction(
                        identifier: "PAUSE_COMPUTE",
                        title: "暂停计算",
                        options: []
                    )
                ]
            case .securityAlert:
                return [
                    UNNotificationAction(
                        identifier: "VIEW_SECURITY",
                        title: "查看安全详情",
                        options: .foreground
                    ),
                    UNNotificationAction(
                        identifier: "ACKNOWLEDGE",
                        title: "确认已处理",
                        options: []
                    ),
                    UNNotificationAction(
                        identifier: "EMERGENCY",
                        title: "紧急联系",
                        options: .destructive
                    )
                ]
            }
        }
    }

    // MARK: - Notification Types

    enum NotificationType: String {
        case communityMessage = "community_message"
        case communityEvent = "community_event"
        case taskAssigned = "task_assigned"
        case taskCompleted = "task_completed"
        case taskExpired = "task_expired"
        case jobClaimed = "job_claimed"
        case jobCompleted = "job_completed"
        case jobFailed = "job_failed"
        case deviceMoved = "device_moved"
        case suspiciousActivity = "suspicious_activity"
        case unauthorizedAccess = "unauthorized_access"

        var category: NotificationCategory {
            switch self {
            case .communityMessage, .communityEvent:
                return .communityAlert
            case .taskAssigned, .taskCompleted, .taskExpired:
                return .taskUpdate
            case .jobClaimed, .jobCompleted, .jobFailed:
                return .edgeJob
            case .deviceMoved, .suspiciousActivity, .unauthorizedAccess:
                return .securityAlert
            }
        }
    }

    // MARK: - Private Properties

    private let notificationCenter = UNUserNotificationCenter.current()
    private var cancellables = Set<AnyCancellable>()

    // MARK: - Singleton

    static let shared = PushNotificationService()

    private override init() {
        super.init()
        setupNotificationCategories()
        setupDelegate()
    }

    // MARK: - Setup

    private func setupNotificationCategories() {
        for category in NotificationCategory.allCases {
            let categoryObj = UNNotificationCategory(
                identifier: category.identifier,
                actions: category.actions,
                intentIdentifiers: [],
                options: [.customDismissAction]
            )
            notificationCenter.setNotificationCategories([categoryObj])
        }
    }

    private func setupDelegate() {
        notificationCenter.delegate = self
    }

    // MARK: - Permission Management

    /// Request notification permission from the user
    func requestPermission() async -> Bool {
        do {
            let options: UNAuthorizationOptions = [.alert, .sound, .badge]
            let granted = try await notificationCenter.requestAuthorization(options: options)
            await MainActor.run {
                isPermissionGranted = granted
            }

            if granted {
                print("Notification permission granted")
                await getNotificationSettings()
            } else {
                print("Notification permission denied")
            }

            return granted
        } catch {
            print("Error requesting notification permission: \(error.localizedDescription)")
            await MainActor.run {
                isPermissionGranted = false
            }
            return false
        }
    }

    /// Get current notification settings
    func getNotificationSettings() async {
        let settings = await notificationCenter.notificationSettings()
        await MainActor.run {
            isPermissionGranted = settings.authorizationStatus == .authorized
            isEnabled = settings.authorizationStatus != .denied
        }

        print("Notification settings - Status: \(settings.authorizationStatus.rawValue), " +
              "Alert: \(settings.alertSetting.rawValue), " +
              "Sound: \(settings.soundSetting.rawValue), " +
              "Badge: \(settings.badgeSetting.rawValue)")
    }

    /// Open app settings for notifications
    func openAppSettings() {
        if let settingsURL = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(settingsURL)
        }
    }

    // MARK: - Remote Push Registration

    /// Register for remote push notifications
    func registerForRemotePush() {
        DispatchQueue.main.async {
            UIApplication.shared.registerForRemoteNotifications()
        }
    }

    /// Handle successful device token registration
    func didRegisterForRemoteNotifications(deviceToken: Data) {
        let tokenString = deviceToken.map { String(format: "%02.2hhx", $0) }.joined()
        print("Device token registered: \(tokenString)")

        Task { @MainActor in
            self.deviceToken = tokenString
            await self.sendDeviceTokenToServer(token: tokenString)
        }
    }

    /// Handle failed device token registration
    func didFailToRegisterForRemoteNotifications(error: Error) {
        print("Failed to register for remote notifications: \(error.localizedDescription)")

        Task { @MainActor in
            self.deviceToken = nil
        }
    }

    /// Send device token to server
    private func sendDeviceTokenToServer(token: String) async {
        // TODO: Implement actual API call to send token to server
        print("Sending device token to server: \(token)")

        // Example implementation:
        /*
        do {
            let url = URL(string: "https://api.clawphones.com/v1/device/register-token")!
            var request = URLRequest(url: url)
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")

            let body = ["device_token": token, "platform": "ios"]
            request.httpBody = try JSONEncoder().encode(body)

            let (_, response) = try await URLSession.shared.data(for: request)
            if let httpResponse = response as? HTTPURLResponse,
               httpResponse.statusCode == 200 {
                print("Device token sent to server successfully")
            }
        } catch {
            print("Failed to send device token: \(error.localizedDescription)")
        }
        */
    }

    // MARK: - Local Notifications

    /// Schedule a local notification
    func scheduleLocalNotification(
        title: String,
        body: String,
        type: NotificationType,
        userInfo: [String: Any]? = nil,
        delay: TimeInterval? = nil
    ) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        content.categoryIdentifier = type.category.identifier
        let count = UIApplication.shared.applicationIconBadgeNumber ?? 0
        content.badge = NSNumber(value: count + 1)

        // Add custom data
        if var userInfo = userInfo {
            userInfo["notification_type"] = type.rawValue
            content.userInfo = userInfo
        } else {
            content.userInfo = ["notification_type": type.rawValue]
        }

        let trigger: UNNotificationTrigger
        if let delay = delay {
            trigger = UNTimeIntervalNotificationTrigger(timeInterval: delay, repeats: false)
        } else {
            trigger = UNTimeIntervalNotificationTrigger(timeInterval: 1, repeats: false)
        }

        let request = UNNotificationRequest(
            identifier: UUID().uuidString,
            content: content,
            trigger: trigger
        )

        notificationCenter.add(request) { error in
            if let error = error {
                print("Failed to schedule local notification: \(error.localizedDescription)")
            } else {
                print("Local notification scheduled: \(title)")
            }
        }
    }

    /// Cancel all pending notifications
    func cancelAllNotifications() {
        notificationCenter.removeAllPendingNotificationRequests()
        notificationCenter.removeAllDeliveredNotifications()
    }

    /// Cancel specific pending notifications with identifiers
    func cancelNotifications(withIdentifiers identifiers: [String]) {
        notificationCenter.removePendingNotificationRequests(withIdentifiers: identifiers)
    }

    /// Clear badge count
    func clearBadge() {
        UIApplication.shared.applicationIconBadgeNumber = 0
    }

    // MARK: - Notification Handling

    /// Handle notification action
    func handleNotificationAction(
        identifier: String,
        notification: UNNotification
    ) {
        let userInfo = notification.request.content.userInfo
        let typeRaw = userInfo["notification_type"] as? String ?? ""
        let type = NotificationType(rawValue: typeRaw)

        print("Notification action: \(identifier), type: \(type?.rawValue ?? "unknown")")

        switch identifier {
        case "VIEW_ALERT":
            // Navigate to alert detail view
            NotificationCenter.default.post(
                name: Notification.Name("OpenAlertDetail"),
                object: nil,
                userInfo: userInfo
            )
        case "VIEW_TASK":
            // Navigate to task detail view
            NotificationCenter.default.post(
                name: Notification.Name("OpenTaskDetail"),
                object: nil,
                userInfo: userInfo
            )
        case "VIEW_JOB":
            // Navigate to edge compute job view
            NotificationCenter.default.post(
                name: Notification.Name("OpenEdgeJobDetail"),
                object: nil,
                userInfo: userInfo
            )
        case "ACCEPT_TASK":
            // Accept a task
            NotificationCenter.default.post(
                name: Notification.Name("AcceptTask"),
                object: nil,
                userInfo: userInfo
            )
        case "START_COMPUTE":
            // Start edge compute processing
            NotificationCenter.default.post(
                name: Notification.Name("StartCompute"),
                object: nil,
                userInfo: userInfo
            )
        case "PAUSE_COMPUTE":
            // Pause edge compute processing
            NotificationCenter.default.post(
                name: Notification.Name("PauseCompute"),
                object: nil,
                userInfo: userInfo
            )
        case "VIEW_SECURITY":
            // Navigate to security view
            NotificationCenter.default.post(
                name: Notification.Name("OpenSecurityView"),
                object: nil,
                userInfo: userInfo
            )
        case "ACKNOWLEDGE":
            // Acknowledge security alert
            NotificationCenter.default.post(
                name: Notification.Name("AcknowledgeSecurityAlert"),
                object: nil,
                userInfo: userInfo
            )
        case "EMERGENCY":
            // Trigger emergency contact
            NotificationCenter.default.post(
                name: Notification.Name("EmergencyContact"),
                object: nil,
                userInfo: userInfo
            )
        case "DISMISS_ALERT":
            // Dismiss the notification
            break
        default:
            break
        }
    }

    /// Handle incoming remote notification
    func handleRemoteNotification(userInfo: [AnyHashable: Any]) {
        print("Remote notification received: \(userInfo)")

        // Post notification for app to handle
        NotificationCenter.default.post(
            name: Notification.Name("RemoteNotificationReceived"),
            object: nil,
            userInfo: userInfo as? [String: Any]
        )
    }

    // MARK: - Notification Routing

    /// Route notification to appropriate view based on type
    func routeNotification(_ userInfo: [String: Any]) -> String? {
        guard let typeRaw = userInfo["notification_type"] as? String else {
            return nil
        }

        let type = NotificationType(rawValue: typeRaw)

        switch type {
        case .communityMessage, .communityEvent:
            return "community"
        case .taskAssigned, .taskCompleted, .taskExpired:
            return "tasks"
        case .jobClaimed, .jobCompleted, .jobFailed:
            return "edge"
        case .deviceMoved, .suspiciousActivity, .unauthorizedAccess:
            return "security"
        case .none:
            return nil
        }
    }
}

// MARK: - UNUserNotificationCenterDelegate

extension PushNotificationService: UNUserNotificationCenterDelegate {

    /// Handle notification presentation when app is in foreground
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        print("Notification received in foreground: \(notification.request.content.title)")

        // Decide whether to show notification in foreground
        let options: UNNotificationPresentationOptions = [.banner, .sound, .badge]
        completionHandler(options)

        // Also notify the app about the notification
        handleRemoteNotification(userInfo: notification.request.content.userInfo)
    }

    /// Handle user tap on notification
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let userInfo = response.notification.request.content.userInfo

        print("Notification tapped: \(response.actionIdentifier)")

        // Handle action if it's not the default tap
        if response.actionIdentifier != UNNotificationDefaultActionIdentifier {
            handleNotificationAction(
                identifier: response.actionIdentifier,
                notification: response.notification
            )
        } else {
            // Default tap - route to appropriate view
            if let route = routeNotification(userInfo as? [String: Any] ?? [:]) {
                NotificationCenter.default.post(
                    name: Notification.Name("NavigateToTab"),
                    object: nil,
                    userInfo: ["route": route, "data": userInfo]
                )
            }
        }

        // Clear badge
        clearBadge()

        completionHandler()
    }
}

// MARK: - Convenience Extensions

extension UIApplication {
    /// Register for remote notifications with PushNotificationService
    func registerForPushNotifications() async -> Bool {
        let service = PushNotificationService.shared
        let granted = await service.requestPermission()

        if granted {
            service.registerForRemotePush()
        }

        return granted
    }
}

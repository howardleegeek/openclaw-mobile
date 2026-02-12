//
//  ClawPhonesApp.swift
//  ClawPhones
//

import Foundation
import SwiftUI
import UIKit

@main
struct ClawPhonesApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate
    @StateObject private var startupCoordinator = AppStartupCoordinator()
    private let alertManager = AlertManager.shared

    var body: some Scene {
        WindowGroup {
            ContentView()
                .task {
                    _ = alertManager
                    startupCoordinator.startDeferredInitialization()
                }
        }
    }
}

// MARK: - AppDelegate

@MainActor
class AppDelegate: NSObject, UIApplicationDelegate {
    let pushNotificationService = PushNotificationService.shared

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // Register for remote notifications
        application.registerForRemoteNotifications()

        // Check notification settings
        Task {
            await pushNotificationService.getNotificationSettings()
        }

        return true
    }

    func application(
        _ application: UIApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        pushNotificationService.didRegisterForRemoteNotifications(deviceToken: deviceToken)
    }

    func application(
        _ application: UIApplication,
        didFailToRegisterForRemoteNotificationsWithError error: Error
    ) {
        pushNotificationService.didFailToRegisterForRemoteNotifications(error: error)
    }

    func application(
        _ application: UIApplication,
        didReceiveRemoteNotification userInfo: [AnyHashable: Any],
        fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void
    ) {
        pushNotificationService.handleRemoteNotification(userInfo: userInfo)
        completionHandler(.newData)
    }
}

@MainActor
private final class AppStartupCoordinator: ObservableObject {
    private let services = LazyStartupServices()
    private var hasStarted = false

    func startDeferredInitialization() {
        guard !hasStarted else { return }
        hasStarted = true

        let services = services
        DispatchQueue.global(qos: .utility).async {
            _ = services.api
            _ = services.cache
            services.analytics.track("app_open", properties: ["platform": "ios"])
            services.crashReporter.uploadPendingReports()
        }
    }
}

private final class LazyStartupServices {
    lazy var crashReporter: CrashReporter = CrashReporter.shared
    lazy var api: OpenClawAPI = OpenClawAPI.shared
    lazy var cache: ConversationCache = ConversationCache.shared
    lazy var analytics: AnalyticsService = AnalyticsService.shared
}

//
//  ClawPhonesApp.swift
//  ClawPhones
//

import Foundation
import SwiftUI

@main
struct ClawPhonesApp: App {
    @StateObject private var startupCoordinator = AppStartupCoordinator()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .task {
                    startupCoordinator.startDeferredInitialization()
                }
        }
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

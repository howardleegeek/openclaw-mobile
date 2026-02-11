//
//  ClawPhonesApp.swift
//  ClawPhones
//

import SwiftUI

@main
struct ClawPhonesApp: App {
    init() {
        CrashReporter.shared.uploadPendingReports()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

//
//  ContentView.swift
//  ClawPhones
//

import SwiftUI

struct ContentView: View {
    @State private var isProvisioned: Bool = DeviceConfig.shared.isProvisioned

    var body: some View {
        NavigationStack {
            Group {
                if isProvisioned {
                    ConversationListView()
                } else {
                    SetupView {
                        isProvisioned = true
                    }
                }
            }
        }
        .onAppear {
            isProvisioned = DeviceConfig.shared.isProvisioned
        }
    }
}

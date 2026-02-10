//
//  SetupView.swift
//  ClawPhones
//

import SwiftUI

struct SetupView: View {
    let onProvisioned: () -> Void

    @State private var token: String = ""
    @State private var errorMessage: String?

    var body: some View {
        Form {
            Section {
                Text("This app requires an OpenClaw device token.")
                    .foregroundStyle(.secondary)

                Text("Base URL: \(DeviceConfig.shared.baseURL)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Section("Device Token") {
                SecureField("Paste token", text: $token)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled(true)
            }

            Section {
                Button("Save") {
                    save()
                }
                .disabled(token.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .navigationTitle("Setup")
        .alert("Error", isPresented: Binding(
            get: { errorMessage != nil },
            set: { newValue in
                if !newValue {
                    errorMessage = nil
                }
            }
        )) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(errorMessage ?? "")
        }
    }

    private func save() {
        let trimmed = token.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            errorMessage = "Token cannot be empty."
            return
        }

        DeviceConfig.shared.saveUserToken(trimmed)

        guard DeviceConfig.shared.isProvisioned else {
            errorMessage = "Failed to save token. Please try again."
            return
        }

        onProvisioned()
    }
}

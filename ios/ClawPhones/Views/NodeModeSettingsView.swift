//
//  NodeModeSettingsView.swift
//  ClawPhones
//

import SwiftUI

struct NodeModeSettingsView: View {
    @State private var voiceConfig = VoiceResponder.TriggerConfig.loadFromDefaults()

    var body: some View {
        Form {
            Section("Voice Response") {
                Toggle("Enable Voice Response", isOn: $voiceConfig.enabled)

                Picker("Response", selection: $voiceConfig.responseType) {
                    ForEach(VoiceResponder.ResponseType.allCases) { type in
                        Text(type.displayName).tag(type)
                    }
                }

                if voiceConfig.responseType == .custom {
                    TextField("Custom Message", text: $voiceConfig.customMessage, axis: .vertical)
                        .lineLimit(2 ... 4)
                }

                Toggle("Only For Person", isOn: $voiceConfig.onlyForPerson)

                HStack {
                    Text("Cooldown")
                    Spacer()
                    Text("\(Int(voiceConfig.cooldown))s")
                        .foregroundStyle(.secondary)
                }

                Slider(value: $voiceConfig.cooldown, in: 10 ... 300, step: 5)
            }

            Section {
                Text("When ClawVision detects a person, this message is announced by the phone speaker.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Node Settings")
        .onAppear {
            voiceConfig = VoiceResponder.TriggerConfig.loadFromDefaults()
        }
        .onChange(of: voiceConfig) { _, newValue in
            var config = newValue
            config.cooldown = max(1, config.cooldown)
            config.saveToDefaults()
        }
    }
}

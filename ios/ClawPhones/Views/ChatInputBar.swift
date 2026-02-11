//
//  ChatInputBar.swift
//  ClawPhones
//

import SwiftUI

struct ChatInputBar: View {
    @ObservedObject private var speechRecognizer = SpeechRecognizer.shared

    @Binding var text: String
    let isLoading: Bool
    let onSend: () -> Void
    @State private var isPressingMic: Bool = false
    @State private var pulseExpanded: Bool = false

    private let speechAccent = Color(red: 232.0 / 255.0, green: 168.0 / 255.0, blue: 83.0 / 255.0)

    private var canSend: Bool {
        !isLoading && !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private var speechStatusText: String {
        switch speechRecognizer.interactionState {
        case .idle:
            return SpeechRecognizer.InteractionState.idle.statusText
        case .listening:
            let partial = speechRecognizer.transcript.trimmingCharacters(in: .whitespacesAndNewlines)
            if partial.isEmpty {
                return SpeechRecognizer.InteractionState.listening.statusText
            }
            return "录音中：\(partial)"
        case .processing:
            return SpeechRecognizer.InteractionState.processing.statusText
        case .done:
            return SpeechRecognizer.InteractionState.done.statusText
        }
    }

    private var speechStatusColor: Color {
        switch speechRecognizer.interactionState {
        case .idle:
            return .secondary
        case .listening, .processing, .done:
            return speechAccent
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 10) {
                TextField("Message", text: $text, axis: .vertical)
                    .lineLimit(1...4)
                    .textInputAutocapitalization(.sentences)
                    .disableAutocorrection(false)
                    .submitLabel(.send)
                    .onSubmit {
                        if canSend {
                            onSend()
                        }
                    }
                micButton

                Button(action: onSend) {
                    Image(systemName: "paperplane.fill")
                        .font(.system(size: 17, weight: .semibold))
                }
                .disabled(!canSend)
                .buttonStyle(.borderless)
            }

            Text(speechStatusText)
                .font(.caption2.weight(.medium))
                .foregroundStyle(speechStatusColor)
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(.thinMaterial)
        .onChange(of: speechRecognizer.transcript) { newValue in
            if speechRecognizer.isListening {
                text = newValue
            }
        }
        .onDisappear {
            speechRecognizer.stopListening()
        }
        .onAppear {
            syncPulseState()
        }
        .onChange(of: speechRecognizer.interactionState) { _ in
            syncPulseState()
        }
    }

    private var micButton: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(speechAccent)
                .opacity(speechRecognizer.interactionState == .listening ? (pulseExpanded ? 0.0 : 0.26) : 0.0)
                .scaleEffect(speechRecognizer.interactionState == .listening ? (pulseExpanded ? 1.3 : 1.0) : 1.0)
                .allowsHitTesting(false)

            Image(systemName: speechRecognizer.interactionState == .listening ? "mic.fill" : "mic")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(speechRecognizer.interactionState == .listening ? Color.black.opacity(0.82) : speechAccent)
                .frame(width: 44, height: 44)
                .background(
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .fill(speechRecognizer.interactionState == .listening ? speechAccent : Color.clear)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .stroke(speechAccent, lineWidth: 2)
                )
                .shadow(
                    color: speechRecognizer.interactionState == .listening ? speechAccent.opacity(0.45) : .clear,
                    radius: speechRecognizer.interactionState == .listening ? 10 : 0,
                    y: 2
                )
                .scaleEffect(speechRecognizer.interactionState == .processing ? 1.03 : 1.0)
        }
        .frame(width: 44, height: 44)
        .contentShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { _ in
                        guard !isPressingMic, !isLoading else { return }
                        isPressingMic = true
                        Task { await speechRecognizer.startListening() }
                    }
                    .onEnded { _ in
                        guard isPressingMic else { return }
                        isPressingMic = false
                        let recognizedText = speechRecognizer.finishListening()
                        guard !recognizedText.isEmpty else { return }
                        text = recognizedText
                        onSend()
                    }
            )
    }

    private func syncPulseState() {
        guard speechRecognizer.interactionState == .listening else {
            withAnimation(.easeOut(duration: 0.2)) {
                pulseExpanded = false
            }
            return
        }

        pulseExpanded = false
        withAnimation(.easeOut(duration: 0.9).repeatForever(autoreverses: false)) {
            pulseExpanded = true
        }
    }
}

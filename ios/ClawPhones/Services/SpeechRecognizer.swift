//
//  SpeechRecognizer.swift
//  ClawPhones
//

import AVFoundation
import Foundation
import Speech

@MainActor
final class SpeechRecognizer: NSObject, ObservableObject {
    enum InteractionState {
        case idle
        case listening
        case processing
        case done

        var statusText: String {
            switch self {
            case .idle:
                return "按住说话"
            case .listening:
                return "录音中…"
            case .processing:
                return "识别中…"
            case .done:
                return "识别完成"
            }
        }
    }

    static let shared = SpeechRecognizer()

    @Published private(set) var transcript: String = ""
    @Published private(set) var isListening: Bool = false
    @Published private(set) var lastError: String?
    @Published private(set) var interactionState: InteractionState = .idle

    private let audioEngine = AVAudioEngine()
    private let speechRecognizer = SFSpeechRecognizer(locale: Locale.current)
    private var recognitionRequest: SFSpeechAudioBufferRecognitionRequest?
    private var recognitionTask: SFSpeechRecognitionTask?
    private var resetToIdleTask: Task<Void, Never>?
    private var processingToDoneTask: Task<Void, Never>?

    private override init() {
        super.init()
    }

    func requestPermissions() async -> Bool {
        let micGranted = await requestMicrophonePermission()
        let speechGranted = await requestSpeechPermission()

        if !micGranted || !speechGranted {
            lastError = "请在系统设置中开启麦克风和语音识别权限。"
            return false
        }

        return true
    }

    func startListening() async {
        lastError = nil
        resetToIdleTask?.cancel()
        processingToDoneTask?.cancel()

        guard await requestPermissions() else {
            stopListening()
            return
        }

        guard let speechRecognizer, speechRecognizer.isAvailable else {
            lastError = "语音识别当前不可用，请稍后再试。"
            stopListening()
            return
        }

        stopListening(resetState: false)
        transcript = ""

        do {
            let audioSession = AVAudioSession.sharedInstance()
            try audioSession.setCategory(.record, mode: .measurement, options: [.duckOthers])
            try audioSession.setActive(true, options: .notifyOthersOnDeactivation)

            let request = SFSpeechAudioBufferRecognitionRequest()
            request.shouldReportPartialResults = true
            request.taskHint = .dictation
            recognitionRequest = request

            let inputNode = audioEngine.inputNode
            let format = inputNode.outputFormat(forBus: 0)
            inputNode.removeTap(onBus: 0)
            inputNode.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buffer, _ in
                self?.recognitionRequest?.append(buffer)
            }

            audioEngine.prepare()
            try audioEngine.start()
            isListening = true
            setInteractionState(.listening)

            recognitionTask = speechRecognizer.recognitionTask(with: request) { [weak self] result, error in
                guard let self else { return }

                if let result {
                    Task { @MainActor in
                        self.transcript = result.bestTranscription.formattedString
                    }
                }

                if let error {
                    Task { @MainActor in
                        self.lastError = error.localizedDescription
                        self.stopListening()
                    }
                    return
                }

                if result?.isFinal == true {
                    Task { @MainActor in
                        self.stopListening(resetState: false)
                        self.setInteractionState(.processing)
                        self.scheduleDoneTransition()
                    }
                }
            }
        } catch {
            lastError = error.localizedDescription
            stopListening()
        }
    }

    @discardableResult
    func finishListening() -> String {
        let finalText = transcript.trimmingCharacters(in: .whitespacesAndNewlines)
        setInteractionState(.processing)
        stopListening(resetState: false)
        if finalText.isEmpty {
            setInteractionState(.idle)
        } else {
            scheduleDoneTransition()
        }
        return finalText
    }

    func stopListening(resetState: Bool = true) {
        if audioEngine.isRunning {
            audioEngine.stop()
        }

        audioEngine.inputNode.removeTap(onBus: 0)
        recognitionRequest?.endAudio()

        recognitionTask?.cancel()
        recognitionTask = nil
        recognitionRequest = nil
        isListening = false
        if resetState {
            processingToDoneTask?.cancel()
            processingToDoneTask = nil
        }
        if resetState {
            setInteractionState(.idle)
        }

        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private func setInteractionState(_ state: InteractionState) {
        interactionState = state
    }

    private func markDone() {
        processingToDoneTask?.cancel()
        processingToDoneTask = nil
        setInteractionState(.done)
        resetToIdleTask?.cancel()
        resetToIdleTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            guard !Task.isCancelled else { return }
            await MainActor.run {
                self?.setInteractionState(.idle)
            }
        }
    }

    private func scheduleDoneTransition() {
        processingToDoneTask?.cancel()
        processingToDoneTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 250_000_000)
            guard !Task.isCancelled else { return }
            await MainActor.run {
                self?.markDone()
            }
        }
    }

    private func requestMicrophonePermission() async -> Bool {
        switch AVAudioSession.sharedInstance().recordPermission {
        case .granted:
            return true
        case .denied:
            return false
        case .undetermined:
            return await withCheckedContinuation { continuation in
                AVAudioSession.sharedInstance().requestRecordPermission { granted in
                    continuation.resume(returning: granted)
                }
            }
        @unknown default:
            return false
        }
    }

    private func requestSpeechPermission() async -> Bool {
        switch SFSpeechRecognizer.authorizationStatus() {
        case .authorized:
            return true
        case .denied, .restricted:
            return false
        case .notDetermined:
            return await withCheckedContinuation { continuation in
                SFSpeechRecognizer.requestAuthorization { status in
                    continuation.resume(returning: status == .authorized)
                }
            }
        @unknown default:
            return false
        }
    }
}

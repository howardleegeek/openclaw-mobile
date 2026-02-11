//
//  ChatInputBar.swift
//  ClawPhones
//

import PhotosUI
import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct ChatInputBar: View {
    @ObservedObject private var speechRecognizer = SpeechRecognizer.shared

    @Binding var text: String
    let isLoading: Bool
    let pendingFiles: [ChatViewModel.PendingFile]
    let onRemovePendingFile: (String) -> Void
    let onAttachmentPicked: (Data, String, String, UIImage?) -> Void
    let onSend: () -> Void

    @State private var isPressingMic: Bool = false
    @State private var pulseExpanded: Bool = false
    @State private var showAttachmentDialog: Bool = false
    @State private var showPhotoPicker: Bool = false
    @State private var showCamera: Bool = false
    @State private var showFileImporter: Bool = false
    @State private var selectedPhotoItem: PhotosPickerItem?

    private let speechAccent = Color(red: 232.0 / 255.0, green: 168.0 / 255.0, blue: 83.0 / 255.0)

    private var canSend: Bool {
        !isLoading && (!text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !pendingFiles.isEmpty)
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
            if !pendingFiles.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(pendingFiles) { file in
                            PendingFileChip(file: file) {
                                onRemovePendingFile(file.id)
                            }
                        }
                    }
                }
            }

            HStack(spacing: 10) {
                Button {
                    showAttachmentDialog = true
                } label: {
                    Image(systemName: "paperclip")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(speechAccent)
                        .frame(width: 36, height: 36)
                }
                .buttonStyle(.borderless)
                .disabled(isLoading)

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
        .confirmationDialog("添加附件", isPresented: $showAttachmentDialog, titleVisibility: .visible) {
            Button("Photo Library") {
                showPhotoPicker = true
            }
            Button("Camera") {
                showCamera = true
            }
            Button("File") {
                showFileImporter = true
            }
            Button("Cancel", role: .cancel) {}
        }
        .photosPicker(isPresented: $showPhotoPicker, selection: $selectedPhotoItem, matching: .images)
        .onChange(of: selectedPhotoItem) { newItem in
            guard let newItem else { return }
            Task {
                await handlePhotoSelection(item: newItem)
            }
        }
        .sheet(isPresented: $showCamera) {
            CameraImagePicker { image in
                handleCameraImage(image)
            }
        }
        .fileImporter(
            isPresented: $showFileImporter,
            allowedContentTypes: [.image, .pdf, .plainText, .commaSeparatedText, .json, .text]
        ) { result in
            handleFileImport(result: result)
        }
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

    private func handlePhotoSelection(item: PhotosPickerItem) async {
        guard let data = try? await item.loadTransferable(type: Data.self),
              let image = UIImage(data: data),
              let compressed = normalizedJPEGData(from: image) else {
            return
        }
        let filename = "photo_\(Int(Date().timeIntervalSince1970)).jpg"
        onAttachmentPicked(compressed, filename, "image/jpeg", image)
        selectedPhotoItem = nil
    }

    private func handleCameraImage(_ image: UIImage?) {
        guard let image,
              let compressed = normalizedJPEGData(from: image) else {
            return
        }
        let filename = "camera_\(Int(Date().timeIntervalSince1970)).jpg"
        onAttachmentPicked(compressed, filename, "image/jpeg", image)
    }

    private func handleFileImport(result: Result<URL, Error>) {
        guard case let .success(url) = result else { return }

        let canAccess = url.startAccessingSecurityScopedResource()
        defer {
            if canAccess {
                url.stopAccessingSecurityScopedResource()
            }
        }

        do {
            let data = try Data(contentsOf: url)
            let filename = url.lastPathComponent.isEmpty ? "file.bin" : url.lastPathComponent
            let ext = url.pathExtension
            let type = UTType(filenameExtension: ext)
            let mimeType = type?.preferredMIMEType ?? "application/octet-stream"
            let thumbnail: UIImage? = mimeType.hasPrefix("image/") ? UIImage(data: data) : nil
            onAttachmentPicked(data, filename, mimeType, thumbnail)
        } catch {
            return
        }
    }

    private func normalizedJPEGData(from image: UIImage) -> Data? {
        let maxWidth: CGFloat = 1024
        let sourceSize = image.size
        let scale = min(1.0, maxWidth / max(1, sourceSize.width))
        let targetSize = CGSize(width: sourceSize.width * scale, height: sourceSize.height * scale)

        let renderer = UIGraphicsImageRenderer(size: targetSize)
        let rendered = renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: targetSize))
        }
        return rendered.jpegData(compressionQuality: 0.8)
    }
}

private struct PendingFileChip: View {
    let file: ChatViewModel.PendingFile
    let onRemove: () -> Void

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: file.mimeType.hasPrefix("image/") ? "photo" : "doc")
                .font(.caption)
            Text(file.filename)
                .font(.caption)
                .lineLimit(1)
            Button(action: onRemove) {
                Image(systemName: "xmark.circle.fill")
                    .font(.caption)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
        .background(Color.secondary.opacity(0.14))
        .clipShape(Capsule())
    }
}

private struct CameraImagePicker: UIViewControllerRepresentable {
    let onFinish: (UIImage?) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onFinish: onFinish)
    }

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    final class Coordinator: NSObject, UINavigationControllerDelegate, UIImagePickerControllerDelegate {
        let onFinish: (UIImage?) -> Void

        init(onFinish: @escaping (UIImage?) -> Void) {
            self.onFinish = onFinish
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            picker.dismiss(animated: true) {
                self.onFinish(nil)
            }
        }

        func imagePickerController(
            _ picker: UIImagePickerController,
            didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
        ) {
            let image = info[.originalImage] as? UIImage
            picker.dismiss(animated: true) {
                self.onFinish(image)
            }
        }
    }
}

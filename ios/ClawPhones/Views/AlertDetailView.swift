//
//  AlertDetailView.swift
//  ClawPhones
//

import SwiftUI
import UIKit

struct AlertDetailView: View {
    let event: AlertEvent
    var onDeleted: (() -> Void)?

    @Environment(\.dismiss) private var dismiss
    @State private var showShareSheet = false
    @State private var showDeleteAlert = false
    @State private var shareItems: [Any] = []

    private static let timestampFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .medium
        return formatter
    }()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                DetectionFrameView(event: event)
                    .frame(maxWidth: .infinity)
                    .aspectRatio(4 / 3, contentMode: .fit)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .stroke(Color.white.opacity(0.12), lineWidth: 1)
                    )

                VStack(alignment: .leading, spacing: 10) {
                    infoRow(title: "类型", value: event.displayType)
                    infoRow(title: "置信度", value: "\(confidencePercent(event.confidence))%")
                    infoRow(title: "时间", value: Self.timestampFormatter.string(from: event.timestamp))
                    infoRow(
                        title: "GPS 位置",
                        value: String(format: "%.6f, %.6f", event.latitude, event.longitude)
                    )
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(14)
                .background(Color(uiColor: .secondarySystemBackground))
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))

                HStack(spacing: 12) {
                    Button {
                        prepareShareItems()
                    } label: {
                        Label("分享", systemImage: "square.and.arrow.up")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)

                    Button(role: .destructive) {
                        showDeleteAlert = true
                    } label: {
                        Label("删除", systemImage: "trash")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                }
            }
            .padding(16)
        }
        .navigationTitle("事件详情")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showShareSheet) {
            ActivitySheet(items: shareItems)
        }
        .alert("确认删除该事件？", isPresented: $showDeleteAlert) {
            Button("取消", role: .cancel) {}
            Button("删除", role: .destructive) {
                deleteEvent()
            }
        }
    }

    private func infoRow(title: String, value: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text(title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.secondary)
                .frame(width: 72, alignment: .leading)
            Text(value)
                .font(.subheadline)
                .foregroundStyle(.primary)
        }
    }

    private func confidencePercent(_ value: Float) -> Int {
        if value <= 1 {
            return max(0, min(100, Int((value * 100).rounded())))
        }
        return max(0, min(100, Int(value.rounded())))
    }

    private func prepareShareItems() {
        var items: [Any] = []
        if let data = event.thumbnailData, let image = UIImage(data: data) {
            items.append(image)
        }
        items.append(
            "ClawVision 事件\n类型: \(event.displayType)\n置信度: \(confidencePercent(event.confidence))%\n时间: \(Self.timestampFormatter.string(from: event.timestamp))\n位置: \(String(format: "%.6f, %.6f", event.latitude, event.longitude))"
        )
        shareItems = items
        showShareSheet = true
    }

    private func deleteEvent() {
        Task {
            await AlertEventStore.shared.deleteEvent(id: event.id)
            await MainActor.run {
                onDeleted?()
                dismiss()
            }
        }
    }
}

private struct DetectionFrameView: View {
    let event: AlertEvent

    var body: some View {
        GeometryReader { proxy in
            ZStack {
                if let data = event.thumbnailData, let image = UIImage(data: data) {
                    let imageSize = image.size
                    let contentRect = fittedRect(container: proxy.size, image: imageSize)

                    Image(uiImage: image)
                        .resizable()
                        .scaledToFit()
                        .frame(width: proxy.size.width, height: proxy.size.height)

                    if let boundingBox = event.boundingBox,
                       let renderedRect = renderedBoundingBox(
                           boundingBox: boundingBox,
                           imageSize: imageSize,
                           contentRect: contentRect
                       ) {
                        RoundedRectangle(cornerRadius: 4, style: .continuous)
                            .stroke(Color.red, lineWidth: 3)
                            .frame(width: renderedRect.width, height: renderedRect.height)
                            .position(
                                x: renderedRect.midX,
                                y: renderedRect.midY
                            )
                    }
                } else {
                    ZStack {
                        Color(uiColor: .secondarySystemBackground)
                        VStack(spacing: 8) {
                            Image(systemName: "photo")
                                .font(.title2)
                                .foregroundStyle(.secondary)
                            Text("暂无检测帧")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
    }

    private func fittedRect(container: CGSize, image: CGSize) -> CGRect {
        guard image.width > 0, image.height > 0 else {
            return CGRect(origin: .zero, size: container)
        }

        let scale = min(container.width / image.width, container.height / image.height)
        let width = image.width * scale
        let height = image.height * scale
        let x = (container.width - width) / 2
        let y = (container.height - height) / 2
        return CGRect(x: x, y: y, width: width, height: height)
    }

    private func renderedBoundingBox(
        boundingBox: CGRect,
        imageSize: CGSize,
        contentRect: CGRect
    ) -> CGRect? {
        guard contentRect.width > 0, contentRect.height > 0 else { return nil }

        let isNormalized = boundingBox.maxX <= 1.01 && boundingBox.maxY <= 1.01
        let normalizedRect: CGRect

        if isNormalized {
            // Vision normalized coordinates are bottom-left based; SwiftUI drawing is top-left based.
            normalizedRect = CGRect(
                x: boundingBox.origin.x,
                y: 1 - boundingBox.origin.y - boundingBox.height,
                width: boundingBox.width,
                height: boundingBox.height
            )
        } else {
            guard imageSize.width > 0, imageSize.height > 0 else { return nil }
            normalizedRect = CGRect(
                x: boundingBox.origin.x / imageSize.width,
                y: boundingBox.origin.y / imageSize.height,
                width: boundingBox.width / imageSize.width,
                height: boundingBox.height / imageSize.height
            )
        }

        let x = contentRect.minX + normalizedRect.origin.x * contentRect.width
        let y = contentRect.minY + normalizedRect.origin.y * contentRect.height
        let width = normalizedRect.width * contentRect.width
        let height = normalizedRect.height * contentRect.height
        let rect = CGRect(x: x, y: y, width: width, height: height)
        return rect.intersection(contentRect)
    }
}

private struct ActivitySheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

//
//  PrivacyCenterView.swift
//  ClawPhones
//

import SwiftUI

enum LocationPrecision: String, CaseIterable, Identifiable {
    case exact = "exact"
    case city = "city"
    case region = "region"
    case disabled = "disabled"

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .exact: return "精确位置"
        case .city: return "城市级"
        case .region: return "区域级"
        case .disabled: return "禁用"
        }
    }

    var systemImage: String {
        switch self {
        case .exact: return "location.fill"
        case .city: return "building.2.fill"
        case .region: return "map.fill"
        case .disabled: return "location.slash"
        }
    }
}

enum ConsentFeature: String, CaseIterable, Identifiable {
    case faceBlur = "face_blur"
    case plateBlur = "plate_blur"
    case analytics = "analytics"
    case crashReports = "crash_reports"
    case productUpdates = "product_updates"

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .faceBlur: return "人脸模糊处理"
        case .plateBlur: return "车牌模糊处理"
        case .analytics: return "使用分析"
        case .crashReports: return "崩溃报告"
        case .productUpdates: return "产品更新通知"
        }
    }

    var description: String {
        switch self {
        case .faceBlur: return "自动模糊图像中的人脸"
        case .plateBlur: return "自动模糊图像中的车牌"
        case .analytics: return "帮助我们改进产品体验"
        case .crashReports: return "发送崩溃日志以帮助我们修复问题"
        case .productUpdates: return "接收新功能和改进的通知"
        }
    }

    var systemImage: String {
        switch self {
        case .faceBlur: return "eye.slash.fill"
        case .plateBlur: return "car.fill"
        case .analytics: return "chart.bar.fill"
        case .crashReports: return "exclamationmark.bubble.fill"
        case .productUpdates: return "bell.fill"
        }
    }
}

enum DataExportStatus {
    case notRequested
    case processing
    case ready(URL)
    case failed(String)

    var displayText: String {
        switch self {
        case .notRequested: return "未请求"
        case .processing: return "处理中..."
        case .ready: return "准备就绪"
        case .failed(let error): return "失败: \(error)"
        }
    }
}

// PrivacySettings is defined in Models/PrivacyConfig.swift

struct PrivacyCenterView: View {
    @State private var settings = PrivacySettings.default
    @State private var exportStatus: DataExportStatus = .notRequested
    @State private var showDeleteAccountAlert = false
    @State private var deleteAccountStep = 0
    @State private var deleteAccountConfirmText = ""
    @State private var exportShareItem: ExportShareItem?
    @State private var isExporting = false

    var body: some View {
        Form {
            // MARK: - Data Collection
            Section("数据收集") {
                Toggle("人脸模糊", isOn: $settings.faceBlurEnabled)
                Toggle("车牌模糊", isOn: $settings.plateBlurEnabled)

                Picker("位置精度", selection: $settings.locationPrecision) {
                    ForEach(LocationPrecision.allCases) { precision in
                        Label(precision.displayName, systemImage: precision.systemImage)
                            .tag(precision)
                    }
                }

                Toggle("分析数据共享", isOn: $settings.analyticsEnabled)
            } header: {
                Text("数据收集")
            } footer: {
                Text("控制应用收集和共享的数据类型。")
                    .font(.footnote)
            }

            // MARK: - Data Retention
            Section("数据保留") {
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text("保留天数")
                        Spacer()
                        Text("\(settings.dataRetentionDays) 天")
                            .foregroundStyle(.secondary)
                    }

                    Slider(value: Binding(
                        get: { Double(settings.dataRetentionDays) },
                        set: { settings.dataRetentionDays = Int($0) }
                    ), in: 1...365, step: 1)
                }

                Toggle("自动删除过期数据", isOn: $settings.autoDeleteEnabled)
            } header: {
                Text("数据保留")
            } footer: {
                Text("设置数据保留期限和自动删除策略。")
                    .font(.footnote)
            }

            // MARK: - Data Export
            Section("数据导出") {
                Button {
                    requestDataExport()
                } label: {
                    HStack(spacing: 10) {
                        if isExporting {
                            ProgressView()
                                .controlSize(.small)
                        }
                        Text("请求数据导出")
                    }
                }
                .disabled(isExporting || exportStatus == .processing)

                HStack {
                    Text("状态")
                    Spacer()
                    statusIndicator
                }

                if case .ready(let url) = exportStatus {
                    Button {
                        downloadExportedData(url)
                    } label: {
                        Label("下载", systemImage: "square.and.arrow.down.fill")
                    }
                }
            } header: {
                Text("数据导出")
            } footer: {
                Text("请求导出您的所有数据，处理完成后即可下载。")
                    .font(.footnote)
            }

            // MARK: - Consent Management
            Section("权限管理") {
                ForEach(ConsentFeature.allCases) { feature in
                    HStack(spacing: 12) {
                        Image(systemName: feature.systemImage)
                            .font(.title3)
                            .foregroundStyle(.tint)
                            .frame(width: 30)

                        VStack(alignment: .leading, spacing: 2) {
                            Text(feature.displayName)
                                .font(.body)
                            Text(feature.description)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }

                        Spacer()

                        Toggle("", isOn: consentBinding(for: feature))
                    }
                }
            } header: {
                Text("权限管理")
            } footer: {
                Text("管理各项功能的权限授予和撤销。")
                    .font(.footnote)
            }

            // MARK: - Delete Account
            Section {
                Button {
                    deleteAccountStep = 1
                    showDeleteAccountAlert = true
                } label: {
                    HStack {
                        Image(systemName: "person.crop.circle.badge.xmark.fill")
                            .foregroundStyle(.red)
                        Text("删除账户")
                    }
                    .foregroundStyle(.red)
                }
            } header: {
                Text("账户操作")
            } footer: {
                Text("删除账户将永久删除所有数据，此操作不可恢复。")
                    .font(.footnote)
            }
        }
        .navigationTitle("隐私中心")
        .alert(alertTitle, isPresented: $showDeleteAccountAlert) {
            if deleteAccountStep == 1 {
                Button("确认删除", role: .destructive) {
                    deleteAccountStep = 2
                }
                Button("取消", role: .cancel) {}
            } else if deleteAccountStep == 2 {
                TextField("输入 CONFIRM 以确认", text: $deleteAccountConfirmText)
                    .textInputAutocapitalization(.characters)
                Button("永久删除", role: .destructive) {
                    performDeleteAccount()
                }
                .disabled(deleteAccountConfirmText != "CONFIRM")
                Button("取消", role: .cancel) {
                    resetDeleteFlow()
                }
            }
        } message: {
            alertMessage
        }
        .sheet(item: $exportShareItem) { item in
            ActivityShareSheet(activityItems: [item.fileURL])
        }
    }

    private var statusIndicator: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(statusColor)
                .frame(width: 8, height: 8)

            Text(exportStatus.displayText)
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
    }

    private var statusColor: Color {
        switch exportStatus {
        case .notRequested: return .gray
        case .processing: return .orange
        case .ready: return .green
        case .failed: return .red
        }
    }

    private var alertTitle: String {
        switch deleteAccountStep {
        case 1: return "确认删除账户？"
        case 2: return "最后确认"
        default: return ""
        }
    }

    @ViewBuilder
    private var alertMessage: some View {
        switch deleteAccountStep {
        case 1:
            Text("此操作会永久删除您的账户和所有数据，包括对话记录、设置和个人信息，且不可恢复。")
        case 2:
            Text("请输入 \"CONFIRM\" 以确认删除账户。这是最后一步，执行后无法撤销。")
        default:
            EmptyView()
        }
    }

    private func consentBinding(for feature: ConsentFeature) -> Binding<Bool> {
        Binding(
            get: { settings.consents[feature.id, default: false] },
            set: { settings.consents[feature.id] = $0 }
        )
    }

    private func requestDataExport() {
        isExporting = true
        exportStatus = .processing

        Task {
            // Simulate API call
            try? await Task.sleep(nanoseconds: 2_000_000_000)

            // For demo purposes, create a temporary file
            let documentsPath = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            let exportURL = documentsPath.appendingPathComponent("clawphones_export_\(Date().timeIntervalSince1970).json")

            if let data = try? JSONEncoder().encode(settings) {
                try? data.write(to: exportURL)
                await MainActor.run {
                    isExporting = false
                    exportStatus = .ready(exportURL)
                }
            } else {
                await MainActor.run {
                    isExporting = false
                    exportStatus = .failed("创建文件失败")
                }
            }
        }
    }

    private func downloadExportedData(_ url: URL) {
        exportShareItem = ExportShareItem(fileURL: url)
        exportStatus = .notRequested
    }

    private func performDeleteAccount() {
        // Perform actual delete account logic
        resetDeleteFlow()
    }

    private func resetDeleteFlow() {
        deleteAccountStep = 0
        deleteAccountConfirmText = ""
        showDeleteAccountAlert = false
    }
}

private struct ExportShareItem: Identifiable {
    let id = UUID()
    let fileURL: URL
}

private struct ActivityShareSheet: UIViewControllerRepresentable {
    let activityItems: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: activityItems, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

#Preview {
    NavigationStack {
        PrivacyCenterView()
    }
}

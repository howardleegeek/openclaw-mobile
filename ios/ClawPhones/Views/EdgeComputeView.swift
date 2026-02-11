//
//  EdgeComputeView.swift
//  ClawPhones
//
//  View for managing edge compute jobs and monitoring device status
//

import SwiftUI
import UIKit
import Charts

struct EdgeComputeView: View {
    @StateObject private var computeService = EdgeComputeService()
    @State private var isEnabled = false
    @State private var showJobDetail: ComputeJob?
    @State private var isConnecting = false
    @State private var connectionError: Error?

    // Statistics
    @State private var jobsToday = 0
    @State private var jobsWeek = 0
    @State private var jobsMonth = 0
    @State private var creditsToday = 0
    @State private var creditsWeek = 0
    @State private var creditsMonth = 0

    // Device health
    @State private var batteryLevel: Float = 100
    @State private var thermalState: ThermalState = .nominal
    @State private var memoryUsage: Double = 0

    private let refreshInterval: TimeInterval = 5.0

    enum ThermalState: String, CaseIterable {
        case nominal = "nominal"
        case fair = "fair"
        case serious = "serious"
        case critical = "critical"

        var displayName: String {
            switch self {
            case .nominal: return "正常"
            case .fair: return "良好"
            case .serious: return "较热"
            case .critical: return "过热"
            }
        }

        var color: Color {
            switch self {
            case .nominal: return .green
            case .fair: return .yellow
            case .serious: return .orange
            case .critical: return .red
            }
        }
    }

    var body: some View {
        List {
            // Enable/Disable Toggle Section
            Section {
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("边缘算力")
                            .font(.headline)
                        Text(isEnabled ? "已启用 - 正在处理任务" : "已禁用 - 等待启用")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Toggle("", isOn: $isEnabled)
                        .onChange(of: isEnabled) { _, newValue in
                            handleToggleChange(newValue)
                        }
                }
            }

            // Current Job with Progress Ring
            if let currentJob = computeService.currentJob {
                Section("当前任务") {
                    VStack(alignment: .leading, spacing: 16) {
                        HStack {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(currentJob.jobType.displayName)
                                    .font(.headline)
                                Text("奖励: \(currentJob.rewardCredits) 积分")
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()

                            ZStack {
                                Circle()
                                    .stroke(Color(.systemGray5), lineWidth: 8)

                                Circle()
                                    .trim(from: 0, to: computeService.isProcessing ? 0.7 : 1)
                                    .stroke(
                                        Color.blue,
                                        style: StrokeStyle(
                                            lineWidth: 8,
                                            lineCap: .round
                                        )
                                    )
                                    .rotationEffect(Angle(degrees: -90))
                                    .animation(.easeInOut(duration: 1.5).repeatForever(autoreverses: false), value: computeService.isProcessing)

                                Image(systemName: "cpu.fill")
                                    .font(.system(size: 16))
                                    .foregroundStyle(.blue)
                            }
                            .frame(width: 50, height: 50)
                        }

                        ProgressView(value: computeService.isProcessing ? 0.7 : 1)
                            .tint(.blue)
                    }
                    .padding(.vertical, 4)
                }
            } else if isEnabled {
                Section("当前任务") {
                    HStack {
                        ProgressView()
                            .tint(.blue)
                        Text("等待任务中...")
                            .foregroundStyle(.secondary)
                            .font(.subheadline)
                    }
                }
            }

            // Statistics Section
            Section("统计") {
                StatisticRow(title: "今日任务", value: "\(jobsToday)", icon: "checkmark.circle")
                StatisticRow(title: "本周任务", value: "\(jobsWeek)", icon: "calendar")
                StatisticRow(title: "本月任务", value: "\(jobsMonth)", icon: "calendar.circle")
                Divider()
                StatisticRow(title: "今日积分", value: "\(creditsToday)", icon: "star.circle")
                StatisticRow(title: "本周积分", value: "\(creditsWeek)", icon: "star")
                StatisticRow(title: "本月积分", value: "\(creditsMonth)", icon: "star.fill")
            }

            // Device Health Section
            Section("设备健康") {
                HealthRow(
                    title: "电池",
                    value: "\(Int(batteryLevel))%",
                    icon: "battery.100",
                    color: batteryColor
                )
                HealthRow(
                    title: "温度",
                    value: thermalState.displayName,
                    icon: "thermometer",
                    color: thermalState.color
                )
                HealthRow(
                    title: "内存",
                    value: "\(Int(memoryUsage)) MB",
                    icon: "memorychip",
                    color: .blue
                )
            }

            // Capabilities Section
            Section("算力能力") {
                if let capabilities = computeService.nodeCapabilities {
                    CapabilityRow(
                        title: "设备型号",
                        value: capabilities.deviceModel,
                        icon: "iphone"
                    )
                    CapabilityRow(
                        title: "CPU 核心",
                        value: "\(capabilities.cpuCores)",
                        icon: "cpu"
                    )
                    CapabilityRow(
                        title: "总内存",
                        value: "\(capabilities.totalMemoryMB) MB",
                        icon: "memorychip"
                    )
                    CapabilityRow(
                        title: "神经网络引擎",
                        value: capabilities.hasNeuralEngine ? "支持" : "不支持",
                        icon: "brain.head.profile",
                        color: capabilities.hasNeuralEngine ? .green : .gray
                    )
                    CapabilityRow(
                        title: "GPU 计算",
                        value: capabilities.hasGPUSupport ? "支持" : "不支持",
                        icon: "gpu",
                        color: capabilities.hasGPUSupport ? .green : .gray
                    )
                    Divider()
                    Text("支持的任务类型")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    ForEach(capabilities.supportedJobTypes, id: \.self) { jobType in
                        HStack {
                            Image(systemName: "checkmark.circle.fill")
                                .foregroundStyle(.green)
                            Text(jobType.displayName)
                                .font(.subheadline)
                        }
                    }
                } else {
                    Text("加载设备能力中...")
                        .foregroundStyle(.secondary)
                }
            }

            // Job History Section
            Section("任务历史") {
                if computeService.completedJobs.isEmpty && computeService.failedJobs.isEmpty {
                    Text("暂无任务记录")
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .center)
                } else {
                    ForEach(computeService.completedJobs.prefix(10)) { job in
                        JobHistoryRow(job: job, status: .completed)
                            .contentShape(Rectangle())
                            .onTapGesture {
                                showJobDetail = job
                            }
                    }
                    ForEach(computeService.failedJobs.prefix(5)) { job in
                        JobHistoryRow(job: job, status: .failed)
                            .contentShape(Rectangle())
                            .onTapGesture {
                                showJobDetail = job
                            }
                    }
                }
            }
        }
        .navigationTitle("算力")
        .sheet(item: $showJobDetail) { job in
            JobDetailView(job: job)
        }
        .alert("连接错误", isPresented: Binding(
            get: { connectionError != nil },
            set: { _ in connectionError = nil }
        )) {
            Button("确定", role: .cancel) { }
        } message: {
            if let error = connectionError {
                Text(error.localizedDescription)
            }
        }
        .onAppear {
            updateDeviceHealth()
            updateStatistics()
        }
        .onReceive(Timer.publish(every: refreshInterval, on: .main, in: .common).autoconnect()) { _ in
            updateDeviceHealth()
            updateStatistics()
        }
    }

    private func handleToggleChange(_ newValue: Bool) {
        Task {
            isConnecting = true
            do {
                if newValue {
                    try await computeService.register()
                    computeService.startAutoProcessing()
                } else {
                    computeService.stopAutoProcessing()
                }
            } catch {
                isEnabled = false
                connectionError = error
            }
            isConnecting = false
        }
    }

    private var batteryColor: Color {
        if batteryLevel > 50 { return .green }
        if batteryLevel > 20 { return .yellow }
        return .red
    }

    private func updateDeviceHealth() {
        UIDevice.current.isBatteryMonitoringEnabled = true
        batteryLevel = UIDevice.current.batteryLevel * 100

        let thermal = ProcessInfo.processInfo.thermalState
        switch thermal {
        case .nominal:
            thermalState = .nominal
        case .fair:
            thermalState = .fair
        case .serious:
            thermalState = .serious
        case .critical:
            thermalState = .critical
        @unknown default:
            thermalState = .nominal
        }

        let memory = ProcessInfo.processInfo.physicalMemory / (1024 * 1024)
        memoryUsage = Double(memory)
    }

    private func updateStatistics() {
        let completed = computeService.completedJobs
        let today = Calendar.current.startOfDay(for: Date())
        let weekStart = Calendar.current.date(byAdding: .day, value: -7, to: today)!
        let monthStart = Calendar.current.date(byAdding: .day, value: -30, to: today)!

        jobsToday = completed.filter { $0.completedAt ?? Date() >= today }.count
        jobsWeek = completed.filter { $0.completedAt ?? Date() >= weekStart }.count
        jobsMonth = completed.filter { $0.completedAt ?? Date() >= monthStart }.count

        creditsToday = completed.filter { $0.completedAt ?? Date() >= today }.reduce(0) { $0 + $1.rewardCredits }
        creditsWeek = completed.filter { $0.completedAt ?? Date() >= weekStart }.reduce(0) { $0 + $1.rewardCredits }
        creditsMonth = completed.filter { $0.completedAt ?? Date() >= monthStart }.reduce(0) { $0 + $1.rewardCredits }
    }
}

// MARK: - Supporting Views

struct StatisticRow: View {
    let title: String
    let value: String
    let icon: String

    var body: some View {
        HStack {
            Image(systemName: icon)
                .foregroundStyle(.blue)
                .frame(width: 24)
            Text(title)
            Spacer()
            Text(value)
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
    }
}

struct HealthRow: View {
    let title: String
    let value: String
    let icon: String
    let color: Color

    var body: some View {
        HStack {
            Image(systemName: icon)
                .foregroundStyle(color)
                .frame(width: 24)
            Text(title)
            Spacer()
            Text(value)
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
    }
}

struct CapabilityRow: View {
    let title: String
    let value: String
    let icon: String
    var color: Color = .blue

    var body: some View {
        HStack {
            Image(systemName: icon)
                .foregroundStyle(color)
                .frame(width: 24)
            Text(title)
            Spacer()
            Text(value)
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
    }
}

struct JobHistoryRow: View {
    let job: ComputeJob
    let status: JobStatus

    private let dateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = "MM-dd HH:mm"
        return formatter
    }()

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: statusIcon)
                .foregroundStyle(statusColor)
                .font(.system(size: 20))

            VStack(alignment: .leading, spacing: 4) {
                Text(job.jobType.displayName)
                    .font(.subheadline)
                    .fontWeight(.medium)

                HStack {
                    Text(job.completedAt != nil ? dateFormatter.string(from: job.completedAt!) : "无时间")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Spacer()
                    Text("\(job.rewardCredits) 积分")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .padding(.vertical, 2)
    }

    private var statusIcon: String {
        switch status {
        case .completed: return "checkmark.circle.fill"
        case .failed: return "xmark.circle.fill"
        default: return "circle"
        }
    }

    private var statusColor: Color {
        switch status {
        case .completed: return .green
        case .failed: return .red
        default: return .gray
        }
    }
}

struct JobDetailView: View {
    let job: ComputeJob
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section("任务信息") {
                    DetailRow(title: "任务ID", value: job.id)
                    DetailRow(title: "任务类型", value: job.jobType.displayName)
                    DetailRow(title: "状态", value: job.status.displayName)
                    DetailRow(title: "奖励积分", value: "\(job.rewardCredits)")
                }

                if job.estimatedMemoryMB != nil || job.estimatedCPUPercent != nil {
                    Section("资源需求") {
                        if let memory = job.estimatedMemoryMB {
                            DetailRow(title: "预估内存", value: "\(memory) MB")
                        }
                        if let cpu = job.estimatedCPUPercent {
                            DetailRow(title: "预估CPU", value: "\(cpu)%")
                        }
                    }
                }

                if let createdAt = job.createdAt {
                    Section("时间信息") {
                        DetailRow(title: "创建时间", value: formatDate(createdAt))
                        if let claimedAt = job.claimedAt {
                            DetailRow(title: "认领时间", value: formatDate(claimedAt))
                        }
                        if let completedAt = job.completedAt {
                            DetailRow(title: "完成时间", value: formatDate(completedAt))
                        }
                    }
                }

                if let metadata = job.metadata, !metadata.isEmpty {
                    Section("元数据") {
                        ForEach(Array(metadata.keys.sorted()), id: \.self) { key in
                            DetailRow(title: key, value: metadata[key] ?? "")
                        }
                    }
                }
            }
            .navigationTitle("任务详情")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("关闭") { dismiss() }
                }
            }
        }
    }

    private func formatDate(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
        return formatter.string(from: date)
    }
}

struct DetailRow: View {
    let title: String
    let value: String

    var body: some View {
        HStack {
            Text(title)
                .foregroundStyle(.secondary)
            Spacer()
            Text(value)
        }
    }
}

#Preview {
    NavigationStack {
        EdgeComputeView()
    }
}

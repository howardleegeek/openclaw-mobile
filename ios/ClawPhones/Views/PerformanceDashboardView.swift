//
//  PerformanceDashboardView.swift
//  ClawPhones
//
//  View for monitoring system performance metrics and health status
//

import SwiftUI
import Charts

struct PerformanceDashboardView: View {
    // Auto-refresh toggle
    @State private var autoRefresh = false

    // Health Status
    @State private var relayStatus: DashboardHealthStatus = .healthy
    @State private var backendStatus: DashboardHealthStatus = .healthy
    @State private var webSocketStatus: DashboardHealthStatus = .healthy
    @State private var pushStatus: DashboardHealthStatus = .healthy
    @State private var lastCheckedTime = Date()

    // Device Metrics
    @State private var memoryUsage: Double = 45
    private let totalMemory = 8192
    @State private var cpuUsage: Double = 32
    @State private var batteryDrainRate: Double = 0.5
    @State private var frameRate: Double = 60

    // Network Metrics
    @State private var bytesInToday: Int64 = 150_000_000
    @State private var bytesOutToday: Int64 = 85_000_000
    @State private var activeConnections = 12
    @State private var latencyHistory: [(hour: Int, latency: Double)] = []

    // Sync Status
    @State private var pendingUploads = 3
    @State private var pendingDownloads = 1
    @State private var lastSyncTime = Date().addingTimeInterval(-300)
    @State private var hasConflicts = false
    @State private var isSyncing = false

    // Cache Metrics
    @State private var cacheHitRate: Double = 87
    @State private var cacheSize: Int64 = 256_000_000
    @State private var showCacheClearAlert = false

    private let refreshInterval: TimeInterval = 60.0

    enum DashboardHealthStatus {
        case healthy
        case warning
        case critical

        var color: Color {
            switch self {
            case .healthy: return .green
            case .warning: return .yellow
            case .critical: return .red
            }
        }

        var indicator: String {
            switch self {
            case .healthy: return "checkmark.circle.fill"
            case .warning: return "exclamationmark.triangle.fill"
            case .critical: return "xmark.circle.fill"
            }
        }

        var displayName: String {
            switch self {
            case .healthy: return "Healthy"
            case .warning: return "Warning"
            case .critical: return "Critical"
            }
        }
    }

    var body: some View {
        List {
            // Auto-refresh Toggle
            Section {
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("自动刷新")
                            .font(.headline)
                        Text(autoRefresh ? "每60秒刷新" : "手动刷新")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Toggle("", isOn: $autoRefresh)
                }
            }

            // Health Status Section
            Section("健康状态") {
                HealthStatusRow(
                    title: "Relay",
                    status: relayStatus,
                    lastChecked: lastCheckedTime
                )
                HealthStatusRow(
                    title: "Backend",
                    status: backendStatus,
                    lastChecked: lastCheckedTime
                )
                HealthStatusRow(
                    title: "WebSocket",
                    status: webSocketStatus,
                    lastChecked: lastCheckedTime
                )
                HealthStatusRow(
                    title: "Push",
                    status: pushStatus,
                    lastChecked: lastCheckedTime
                )
            } header: {
                HStack {
                    Text("健康状态")
                    Spacer()
                    Text("上次检查: \(formatTime(lastCheckedTime))")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            // Device Metrics Section
            Section("设备指标") {
                GaugeRow(
                    title: "内存",
                    value: memoryUsage,
                    maxValue: Double(totalMemory),
                    unit: "MB",
                    icon: "memorychip",
                    color: .blue
                )
                GaugeRow(
                    title: "CPU",
                    value: cpuUsage,
                    maxValue: 100,
                    unit: "%",
                    icon: "cpu",
                    color: .purple
                )
                MetricRow(
                    title: "电池耗电率",
                    value: "\(batteryDrainRate)%/h",
                    icon: "battery.100.bolt",
                    color: batteryDrainColor
                )
                MetricRow(
                    title: "帧率",
                    value: "\(Int(frameRate)) FPS",
                    icon: "film",
                    color: frameRateColor
                )
            }

            // Network Section
            Section("网络") {
                HStack {
                    Text("今日流入")
                    Spacer()
                    Text(formatBytes(bytesInToday))
                        .foregroundStyle(.secondary)
                }
                HStack {
                    Text("今日流出")
                    Spacer()
                    Text(formatBytes(bytesOutToday))
                        .foregroundStyle(.secondary)
                }
                HStack {
                    Text("活跃连接")
                    Spacer()
                    Text("\(activeConnections)")
                        .foregroundStyle(.secondary)
                }
            }

            // Latency Chart (last 24h)
            Section("网络延迟 (24小时)") {
                VStack(alignment: .leading, spacing: 8) {
                    Chart(latencyHistory) { data in
                        LineMark(
                            x: .value("时间", data.hour),
                            y: .value("延迟 (ms)", data.latency)
                        )
                        .interpolationMethod(.catmullRom)
                        .foregroundStyle(.blue.gradient)

                        AreaMark(
                            x: .value("时间", data.hour),
                            y: .value("延迟 (ms)", data.latency)
                        )
                        .interpolationMethod(.catmullRom)
                        .foregroundStyle(.blue.opacity(0.2).gradient)
                    }
                    .frame(height: 150)
                    .chartXAxis {
                        AxisMarks(values: .stride(by: .hour, count: 4)) { value in
                            AxisValueLabel("\(value.as(Int.self) ?? 0):00")
                                .font(.caption2)
                        }
                    }
                    .chartYAxis {
                        AxisMarks { value in
                            AxisValueLabel()
                                .font(.caption2)
                        }
                    }
                }
                .padding(.vertical, 4)
            }

            // Sync Status Section
            Section("同步状态") {
                HStack {
                    Text("待上传")
                    Spacer()
                    Text("\(pendingUploads)")
                        .foregroundStyle(pendingUploads > 0 ? .orange : .secondary)
                }
                HStack {
                    Text("待下载")
                    Spacer()
                    Text("\(pendingDownloads)")
                        .foregroundStyle(pendingDownloads > 0 ? .orange : .secondary)
                }
                HStack {
                    Text("上次同步")
                    Spacer()
                    Text(formatTime(lastSyncTime))
                        .foregroundStyle(.secondary)
                }
                if hasConflicts {
                    HStack {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .foregroundStyle(.orange)
                        Text("存在冲突需要解决")
                            .foregroundStyle(.orange)
                        Spacer()
                    }
                }
                Button {
                    forceSync()
                } label: {
                    HStack {
                        Spacer()
                        if isSyncing {
                            ProgressView()
                                .tint(.white)
                            Text("同步中...")
                        } else {
                            Image(systemName: "arrow.clockwise")
                            Text("强制同步")
                        }
                        Spacer()
                    }
                }
                .disabled(isSyncing)
                .buttonStyle(.borderedProminent)
            }

            // Cache Section
            Section("缓存") {
                HStack {
                    Text("命中率")
                    Spacer()
                    Text("\(Int(cacheHitRate))%")
                        .foregroundStyle(cacheHitRateColor)
                }
                HStack {
                    Text("缓存大小")
                    Spacer()
                    Text(formatBytes(cacheSize))
                        .foregroundStyle(.secondary)
                }
                Button {
                    showCacheClearAlert = true
                } label: {
                    HStack {
                        Image(systemName: "trash")
                        Text("清除缓存")
                        Spacer()
                    }
                }
                .foregroundStyle(.red)
            }
        }
        .navigationTitle("性能监控")
        .alert("清除缓存", isPresented: $showCacheClearAlert) {
            Button("取消", role: .cancel) { }
            Button("清除", role: .destructive) {
                clearCache()
            }
        } message: {
            Text("确定要清除所有缓存吗？这将释放 \(formatBytes(cacheSize)) 空间。")
        }
        .onAppear {
            generateLatencyHistory()
        }
        .onReceive(Timer.publish(every: refreshInterval, on: .main, in: .common).autoconnect()) { _ in
            if autoRefresh {
                refreshData()
            }
        }
    }

    // MARK: - Helper Methods

    private func refreshData() {
        // Simulate health status changes
        relayStatus = HealthStatus.allCases.randomElement() ?? .healthy
        backendStatus = HealthStatus.allCases.randomElement() ?? .healthy
        webSocketStatus = HealthStatus.allCases.randomElement() ?? .healthy
        pushStatus = HealthStatus.allCases.randomElement() ?? .healthy
        lastCheckedTime = Date()

        // Simulate metric changes
        memoryUsage = Double.random(in: 2048...6144)
        cpuUsage = Double.random(in: 10...80)
        batteryDrainRate = Double.random(in: 0.1...2.0)
        frameRate = Double.random(in: 30...60)

        // Simulate network changes
        activeConnections = Int.random(in: 5...25)
        generateLatencyHistory()

        // Simulate sync changes
        pendingUploads = Int.random(in: 0...10)
        pendingDownloads = Int.random(in: 0...5)
        lastSyncTime = Date().addingTimeInterval(-Double.random(in: 0...600))
        hasConflicts = Bool.random()

        // Simulate cache changes
        cacheHitRate = Double.random(in: 60...98)
        cacheSize = Int64.random(in: 100_000_000...500_000_000)
    }

    private func forceSync() {
        isSyncing = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
            isSyncing = false
            pendingUploads = 0
            pendingDownloads = 0
            lastSyncTime = Date()
            hasConflicts = false
        }
    }

    private func clearCache() {
        cacheSize = 0
        cacheHitRate = 100
    }

    private func generateLatencyHistory() {
        latencyHistory = (0..<24).map { hour in
            (hour: hour, latency: Double.random(in: 10...150))
        }
    }

    private func formatTime(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = "HH:mm:ss"
        return formatter.string(from: date)
    }

    private func formatBytes(_ bytes: Int64) -> String {
        let mb = Double(bytes) / 1_000_000
        if mb >= 1000 {
            return String(format: "%.2f GB", mb / 1000)
        }
        return String(format: "%.1f MB", mb)
    }

    private var batteryDrainColor: Color {
        if batteryDrainRate < 0.5 { return .green }
        if batteryDrainRate < 1.5 { return .yellow }
        return .red
    }

    private var frameRateColor: Color {
        if frameRate >= 50 { return .green }
        if frameRate >= 30 { return .yellow }
        return .red
    }

    private var cacheHitRateColor: Color {
        if cacheHitRate >= 80 { return .green }
        if cacheHitRate >= 60 { return .yellow }
        return .red
    }
}

// MARK: - Supporting Views

struct HealthStatusRow: View {
    let title: String
    let status: PerformanceDashboardView.DashboardHealthStatus
    let lastChecked: Date

    var body: some View {
        HStack {
            Image(systemName: status.indicator)
                .foregroundStyle(status.color)
                .frame(width: 24)
            Text(title)
            Spacer()
            Text(status.displayName)
                .font(.subheadline)
                .foregroundStyle(status.color)
        }
    }
}

// displayName is now part of DashboardHealthStatus enum

struct GaugeRow: View {
    let title: String
    let value: Double
    let maxValue: Double
    let unit: String
    let icon: String
    let color: Color

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .foregroundStyle(color)
                .frame(width: 24)

            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.subheadline)

                HStack(spacing: 8) {
                    ProgressView(value: value / maxValue)
                        .tint(color)

                    Text("\(Int(value))\(unit)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .frame(minWidth: 50, alignment: .trailing)
                }
            }
        }
        .padding(.vertical, 2)
    }
}

struct MetricRow: View {
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
                .foregroundStyle(color)
        }
    }
}

#Preview {
    NavigationStack {
        PerformanceDashboardView()
    }
}

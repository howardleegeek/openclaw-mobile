//
//  ExploreView.swift
//  ClawPhones
//
//  探索页面 - 节点网络、开发者工具和系统功能的中心入口
//

import SwiftUI

struct ExploreView: View {
    @State private var nodeModeStatus: String = "未启用"
    @State private var nodeModeStatusColor: Color = .secondary
    @State private var edgeComputeStatus: String = "在线"
    @State private var edgeComputeStatusColor: Color = .green
    @State private var coverageStatus: String = "3 个节点"
    @State private var coverageStatusColor: Color = .blue

    var body: some View {
        List {
            // 节点网络 Section
            Section("节点网络") {
                NavigationLink(destination: EdgeComputeView()) {
                    ExploreCard(
                        title: "Edge Compute",
                        subtitle: "分布式算力网络",
                        icon: "cpu.fill",
                        iconColor: .blue,
                        status: edgeComputeStatus,
                        statusColor: edgeComputeStatusColor
                    )
                }

                NavigationLink(destination: CoverageMapView()) {
                    ExploreCard(
                        title: "Coverage Map",
                        subtitle: "节点覆盖范围可视化",
                        icon: "map.fill",
                        iconColor: .green,
                        status: coverageStatus,
                        statusColor: coverageStatusColor
                    )
                }

                NavigationLink(destination: NodeModeSettingsView()) {
                    ExploreCard(
                        title: "Node Mode",
                        subtitle: "智能告警与监控",
                        icon: "antenna.radiowaves.left.and.right",
                        iconColor: .orange,
                        status: nodeModeStatus,
                        statusColor: nodeModeStatusColor
                    )
                }
            }

            // 开发者工具 Section
            Section("开发者工具") {
                NavigationLink(destination: DeveloperPortalView()) {
                    ExploreCard(
                        title: "Developer Portal",
                        subtitle: "API 文档与开发工具",
                        icon: "hammer.fill",
                        iconColor: .purple,
                        status: nil,
                        statusColor: nil
                    )
                }

                NavigationLink(destination: UsageDashboardView()) {
                    ExploreCard(
                        title: "Usage Dashboard",
                        subtitle: "资源使用统计与分析",
                        icon: "chart.bar.fill",
                        iconColor: .cyan,
                        status: nil,
                        statusColor: nil
                    )
                }

                NavigationLink(destination: PluginMarketView()) {
                    ExploreCard(
                        title: "Plugin Market",
                        subtitle: "插件与扩展市场",
                        icon: "puzzlepiece.extension.fill",
                        iconColor: .pink,
                        status: nil,
                        statusColor: nil
                    )
                }
            }

            // 系统 Section
            Section("系统") {
                NavigationLink(destination: PerformanceDashboardView()) {
                    ExploreCard(
                        title: "Performance Dashboard",
                        subtitle: "系统性能监控面板",
                        icon: "gauge.with.needle.fill",
                        iconColor: .red,
                        status: nil,
                        statusColor: nil
                    )
                }
            }
        }
        .navigationTitle("探索")
        .onAppear {
            updateNodeModeStatus()
        }
    }

    private func updateNodeModeStatus() {
        // 检查 Node Mode 状态
        let isEnabled = AlertManager.shared.isMonitoringEnabled
        if isEnabled {
            nodeModeStatus = "监控中"
            nodeModeStatusColor = .green
        } else {
            nodeModeStatus = "已暂停"
            nodeModeStatusColor = .orange
        }

        // 检查 Edge Compute 状态
        let isEdgeOnline = EdgeComputeViewModel.shared.isConnected
        edgeComputeStatus = isEdgeOnline ? "在线" : "离线"
        edgeComputeStatusColor = isEdgeOnline ? .green : .red

        // 更新覆盖状态
        let nodeCount = CoverageMapView.nodeCount
        coverageStatus = "\(nodeCount) 个节点"
        coverageStatusColor = nodeCount > 0 ? .blue : .secondary
    }
}

// 探索页面卡片组件
struct ExploreCard: View {
    let title: String
    let subtitle: String
    let icon: String
    let iconColor: Color
    let status: String?
    let statusColor: Color?

    var body: some View {
        HStack(spacing: 16) {
            // 图标容器
            ZStack {
                Circle()
                    .fill(iconColor.opacity(0.15))
                    .frame(width: 56, height: 56)

                Image(systemName: icon)
                    .font(.system(size: 24, weight: .semibold))
                    .foregroundStyle(iconColor)
            }

            // 文本信息
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.headline.weight(.semibold))

                Text(subtitle)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            Spacer()

            // 状态标签（如果有）
            if let status = status, let statusColor = statusColor {
                HStack(spacing: 4) {
                    Circle()
                        .fill(statusColor)
                        .frame(width: 8, height: 8)

                    Text(status)
                        .font(.caption.weight(.medium))
                        .foregroundStyle(statusColor)
                }
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(statusColor.opacity(0.1))
                .clipShape(Capsule())
            }
        }
        .padding(.vertical, 4)
    }
}

// MARK: - 占位视图
// 以下视图为占位实现，如果这些视图已存在，可以删除这些占位

struct NodeModeSettingsView: View {
    var body: some View {
        List {
            Section("Node Mode 设置") {
                Toggle("启用智能监控", isOn: .constant(false))
                Toggle("推送告警通知", isOn: .constant(true))
            }

            Section("检测阈值") {
                HStack {
                    Text("置信度阈值")
                    Spacer()
                    Text("75%")
                        .foregroundStyle(.secondary)
                }

                HStack {
                    Text("检测间隔")
                    Spacer()
                    Text("5 秒")
                        .foregroundStyle(.secondary)
                }
            }

            Section {
                Text("Node Mode 提供智能物体检测和告警功能，需要完整实现。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Node Mode 设置")
    }
}

struct DeveloperPortalView: View {
    var body: some View {
        List {
            Section {
                Text("开发者门户")
                    .font(.title2)
                    .fontWeight(.bold)
                    .padding(.vertical)

                Text("访问 API 文档、SDK 下载和开发者资源。")
                    .foregroundStyle(.secondary)
            } header: {
                Text("开发者资源")
            }

            Section {
                Link(destination: URL(string: "https://docs.example.com")!) {
                    Label("API 文档", systemImage: "book.fill")
                }
                Label("SDK 下载", systemImage: "square.and.arrow.down.fill")
                Label("示例代码", systemImage: "code")
            }
        }
        .navigationTitle("Developer Portal")
    }
}

struct UsageDashboardView: View {
    var body: some View {
        List {
            Section("资源使用概览") {
                HStack {
                    VStack(alignment: .leading) {
                        Text("CPU 使用率")
                            .font(.headline)
                        Text("45%")
                            .font(.title2)
                            .foregroundStyle(.blue)
                    }

                    Spacer()

                    ProgressView(value: 0.45)
                        .frame(width: 100)
                }

                HStack {
                    VStack(alignment: .leading) {
                        Text("内存使用")
                            .font(.headline)
                        Text("1.2 GB")
                            .font(.title2)
                            .foregroundStyle(.green)
                    }

                    Spacer()

                    ProgressView(value: 0.38)
                        .frame(width: 100)
                }
            }

            Section {
                Text("使用情况仪表板需要连接真实数据源。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Usage Dashboard")
    }
}

struct PluginMarketView: View {
    var body: some View {
        List {
            Section {
                Text("插件市场")
                    .font(.title2)
                    .fontWeight(.bold)
                    .padding(.vertical)

                Text("浏览和安装社区插件来扩展应用功能。")
                    .foregroundStyle(.secondary)
            } header: {
                Text("插件中心")
            }

            Section {
                Label("智能翻译插件", systemImage: "globe")
                Label("数据可视化插件", systemImage: "chart.xyaxis.line")
                Label("自动化任务插件", systemImage: "arrow.triangle.2.circlepath")
            }
        }
        .navigationTitle("Plugin Market")
    }
}

struct PerformanceDashboardView: View {
    var body: some View {
        List {
            Section("系统性能") {
                PerformanceRow(title: "响应时间", value: "120ms", color: .green)
                PerformanceRow(title: "错误率", value: "0.12%", color: .green)
                PerformanceRow(title: "可用性", value: "99.8%", color: .green)
            }

            Section("网络状态") {
                PerformanceRow(title: "延迟", value: "45ms", color: .blue)
                PerformanceRow(title: "带宽", value: "85%", color: .orange)
            }

            Section {
                Text("性能仪表板需要连接真实监控系统。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Performance Dashboard")
    }
}

struct PerformanceRow: View {
    let title: String
    let value: String
    let color: Color

    var body: some View {
        HStack {
            Text(title)
            Spacer()
            Text(value)
                .foregroundStyle(color)
                .fontWeight(.semibold)
        }
    }
}

// MARK: - CoverageMapView 扩展
extension CoverageMapView {
    static var nodeCount: Int {
        // 返回节点数量，需要从实际数据源获取
        return 3
    }
}

// MARK: - EdgeComputeViewModel 扩展
extension EdgeComputeViewModel {
    var isConnected: Bool {
        // 返回 Edge Compute 连接状态，需要从实际数据源获取
        return true
    }
}

// MARK: - EdgeComputeViewModel 单例（如果尚未存在）
class EdgeComputeViewModel: ObservableObject {
    static let shared = EdgeComputeViewModel()

    @Published var isConnected = true
    @Published var nodeCount = 3

    private init() {}
}

#Preview {
    NavigationStack {
        ExploreView()
    }
}

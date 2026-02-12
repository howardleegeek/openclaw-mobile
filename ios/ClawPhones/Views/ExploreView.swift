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
// NodeModeSettingsView is defined in Views/NodeModeSettingsView.swift
// DeveloperPortalView is defined in Views/DeveloperPortalView.swift

// UsageDashboardView is defined in Views/UsageDashboardView.swift
// PluginMarketView is defined in Views/PluginMarketView.swift

// PerformanceDashboardView is defined in Views/PerformanceDashboardView.swift

// MARK: - CoverageMapView 扩展
extension CoverageMapView {
    static var nodeCount: Int {
        // 返回节点数量，需要从实际数据源获取
        return 3
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

//
//  EarningsView.swift
//  ClawPhones
//

import SwiftUI

struct EarningsView: View {
    @EnvironmentObject private var auth: AuthViewModel
    @StateObject private var viewModel = EarningsViewModel()

    private static let dateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = "M月d日"
        return formatter
    }()

    var body: some View {
        ScrollView {
            if let earnings = viewModel.earnings {
                earningsContent(earnings: earnings)
            } else if viewModel.isLoading {
                progressView
            } else if viewModel.errorMessage != nil {
                errorView
            }
        }
        .navigationTitle("收益")
        .task(id: auth.isAuthenticated) {
            if auth.isAuthenticated {
                await loadEarnings()
            }
        }
        .refreshable {
            if auth.isAuthenticated {
                await loadEarnings()
            }
        }
        .alert("错误", isPresented: Binding(
            get: { viewModel.errorMessage != nil },
            set: { newValue in
                if !newValue {
                    viewModel.errorMessage = nil
                }
            }
        )) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(viewModel.errorMessage ?? "")
        }
    }

    private func earningsContent(earnings: EarningsSummary) -> some View {
        VStack(spacing: 20) {
            balanceSection(earnings: earnings)

            Divider()
                .padding(.horizontal)

            breakdownSection(earnings: earnings)

            Divider()
                .padding(.horizontal)

            chartSection(earnings: earnings)

            Divider()
                .padding(.horizontal)

            historySection(earnings: earnings)
        }
        .padding()
    }

    private func balanceSection(earnings: EarningsSummary) -> some View {
        VStack(spacing: 12) {
            Text("总积分")
                .font(.subheadline)
                .foregroundStyle(.secondary)

            Text("\(earnings.totalCredits)")
                .font(.system(size: 48, weight: .bold))
                .foregroundStyle(.tint)

            HStack(spacing: 20) {
                statBadge(
                    icon: "checkmark.circle",
                    title: "已完成",
                    value: "\(earnings.completedTasks)"
                )

                statBadge(
                    icon: "flame",
                    title: "连续天数",
                    value: "\(earnings.activeStreak)"
                )

                statBadge(
                    icon: "chart.bar",
                    title: "平均/任务",
                    value: "\(earnings.averagePerTask)"
                )
            }
        }
        .padding(.vertical, 20)
    }

    private func statBadge(icon: String, title: String, value: String) -> some View {
        VStack(spacing: 4) {
            Image(systemName: icon)
                .font(.title3)
                .foregroundStyle(.secondary)

            Text(value)
                .font(.headline)
                .fontWeight(.semibold)

            Text(title)
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
    }

    private func breakdownSection(earnings: EarningsSummary) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("收益明细")
                .font(.headline)

            breakdownRow(title: "今日", value: earnings.todayCredits, color: .blue)
            breakdownRow(title: "本周", value: earnings.weekCredits, color: .green)
            breakdownRow(title: "本月", value: earnings.monthCredits, color: .orange)
        }
    }

    private func breakdownRow(title: String, value: Int, color: Color) -> some View {
        HStack {
            Circle()
                .fill(color)
                .frame(width: 8, height: 8)

            Text(title)
                .font(.subheadline)

            Spacer()

            Text("\(value)")
                .font(.headline)
                .foregroundStyle(color)
        }
    }

    private func chartSection(earnings: EarningsSummary) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("周收益趋势")
                .font(.headline)

            earningsBarChart(earnings: earnings)
        }
    }

    private func earningsBarChart(earnings: EarningsSummary) -> some View {
        let chartData = viewModel.weeklyData
        let maxValue = chartData.map(\.value).max() ?? 1

        return HStack(alignment: .bottom, spacing: 8) {
            ForEach(chartData.indices, id: \.self) { index in
                let item = chartData[index]
                let barHeight = maxValue > 0 ? CGFloat(item.value) / CGFloat(maxValue) : 0

                VStack(spacing: 4) {
                    Text("\(item.value)")
                        .font(.caption2)
                        .foregroundStyle(.secondary)

                    RoundedRectangle(cornerRadius: 4)
                        .fill(item.isToday ? Color.tint : Color.secondary.opacity(0.3))
                        .frame(height: max(20, barHeight * 80))

                    Text(item.dayLabel)
                        .font(.caption2)
                        .foregroundStyle(item.isToday ? .primary : .secondary)
                }
            }
        }
        .frame(height: 120)
    }

    private func historySection(earnings: EarningsSummary) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("任务历史")
                .font(.headline)

            if viewModel.taskHistory.isEmpty {
                Text("暂无任务历史")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .center)
                    .padding(.vertical, 20)
            } else {
                VStack(spacing: 0) {
                    ForEach(viewModel.taskHistory) { item in
                        historyRow(item: item)

                        if item.id != viewModel.taskHistory.last?.id {
                            Divider()
                                .padding(.leading, 60)
                        }
                    }
                }
            }
        }
    }

    private func historyRow(item: TaskHistoryItem) -> some View {
        HStack(spacing: 12) {
            Image(systemName: "checkmark.circle.fill")
                .foregroundStyle(.green)
                .frame(width: 24)

            VStack(alignment: .leading, spacing: 2) {
                Text(item.taskTitle)
                    .font(.subheadline)
                    .lineLimit(1)

                Text(Self.dateFormatter.string(from: item.completedDate))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Spacer()

            Text("+\(item.earnedCredits)")
                .font(.headline)
                .foregroundStyle(.tint)
                .fontWeight(.semibold)
        }
        .padding(.vertical, 8)
    }

    private var progressView: some View {
        VStack(spacing: 16) {
            ProgressView()
            Text("加载中...")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var errorView: some View {
        ContentUnavailableView(
            "加载失败",
            systemImage: "exclamationmark.triangle",
            description: Text(viewModel.errorMessage ?? "未知错误")
        )
    }

    private func loadEarnings() async {
        await viewModel.loadEarnings()
    }
}

// MARK: - Task History Item

struct TaskHistoryItem: Identifiable {
    let id: String
    let taskTitle: String
    let earnedCredits: Int
    let completedDate: Date
}

// MARK: - Earnings View Model

@MainActor
final class EarningsViewModel: ObservableObject {
    @Published var earnings: EarningsSummary?
    @Published var taskHistory: [TaskHistoryItem] = []
    @Published var weeklyData: [WeeklyDataPoint] = []
    @Published var isLoading: Bool = false
    @Published var errorMessage: String?

    func loadEarnings() async {
        isLoading = true
        defer { isLoading = false }

        do {
            let service = TaskMarketService.shared
            earnings = await service.fetchEarnings()

            if let earnings {
                taskHistory = await loadTaskHistory()
                weeklyData = generateWeeklyData(from: earnings)
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func loadTaskHistory() async -> [TaskHistoryItem] {
        do {
            let service = TaskMarketService.shared
            let completedTasks = await service.fetchUserTasks(status: .completed)

            return completedTasks.prefix(10).map { task in
                TaskHistoryItem(
                    id: task.id,
                    taskTitle: task.title,
                    earnedCredits: task.reward.totalCredits,
                    completedDate: task.dates.completedDate ?? Date()
                )
            }
        } catch {
            return []
        }
    }

    private func generateWeeklyData(from earnings: EarningsSummary) -> [WeeklyDataPoint] {
        let calendar = Calendar.current
        let now = Date()
        let weekday = calendar.component(.weekday, from: now)

        var data: [WeeklyDataPoint] = []

        let dayLabels = ["日", "一", "二", "三", "四", "五", "六"]

        for i in 0..<7 {
            let dayIndex = (weekday - 1 + i) % 7
            let isToday = i == 0

            let dataPoint = WeeklyDataPoint(
                dayLabel: dayLabels[dayIndex],
                value: Int.random(in: 0...max(10, earnings.weekCredits / 7)),
                isToday: isToday
            )
            data.append(dataPoint)
        }

        return data
    }
}

struct WeeklyDataPoint {
    let dayLabel: String
    let value: Int
    let isToday: Bool
}

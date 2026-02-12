//
//  TaskDetailView.swift
//  ClawPhones
//

import SwiftUI

struct TaskDetailView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var auth: AuthViewModel
    @StateObject private var viewModel = TaskDetailViewModel()

    let taskId: String

    @State private var showCompletionAlert = false
    @State private var showSuccessAlert = false

    private static let dateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = "M月d日 HH:mm"
        return formatter
    }()

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                if let task = viewModel.task {
                    taskContent(task: task)
                } else if viewModel.isLoading {
                    progressView
                } else if viewModel.errorMessage != nil {
                    errorView
                }
            }
        }
        .navigationTitle(viewModel.task?.title ?? "任务详情")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button("关闭") { dismiss() }
            }
        }
        .task(id: auth.isAuthenticated) {
            if auth.isAuthenticated {
                await loadTask()
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
        .confirmationDialog("确认完成任务？", isPresented: $showCompletionAlert, titleVisibility: .visible) {
            Button("确认完成", role: .destructive) {
                Task {
                    await submitTask()
                }
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("完成后将无法再次修改。")
        }
        .alert("提交成功", isPresented: $showSuccessAlert) {
            Button("确定", role: .cancel) { dismiss() }
        } message: {
            Text("任务已成功提交！")
        }
    }

    private func taskContent(task: ClawTask) -> some View {
        VStack(spacing: 20) {
            taskHeaderSection(task: task)

            Divider()
                .padding(.horizontal)

            requirementsSection(task: task)

            Divider()
                .padding(.horizontal)

            locationSection(task: task)

            Divider()
                .padding(.horizontal)

            rewardSection(task: task)

            Divider()
                .padding(.horizontal)

            progressSection(task: task)

            Spacer()

            actionButtons(task: task)
        }
        .padding()
    }

    private func taskHeaderSection(task: ClawTask) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Image(systemName: task.taskType.icon)
                    .font(.title)
                    .foregroundStyle(.tint)

                Text(task.taskType.displayName)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)

                Spacer()

                statusBadge(for: task.status)
            }

            Text(task.description)
                .font(.body)
        }
    }

    private func statusBadge(for status: TaskStatus) -> some View {
        Text(status.displayName)
            .font(.caption)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(statusColor(for: status).opacity(0.2))
            .foregroundStyle(statusColor(for: status))
            .clipShape(Capsule())
    }

    private func statusColor(for status: TaskStatus) -> Color {
        switch status {
        case .available: return .blue
        case .assigned: return .orange
        case .inProgress: return .yellow
        case .completed: return .green
        case .expired: return .gray
        }
    }

    private func requirementsSection(task: ClawTask) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("任务要求")
                .font(.headline)

            VStack(alignment: .leading, spacing: 8) {
                requirementRow(
                    icon: "clock",
                    title: "预计时长",
                    value: "\(task.requirements.durationMinutes) 分钟"
                )

                if let photos = task.requirements.requiredPhotos {
                    requirementRow(
                        icon: "photo",
                        title: "所需照片",
                        value: "\(photos) 张"
                    )
                }

                if let skill = task.requirements.skillLevel {
                    requirementRow(
                        icon: "star",
                        title: "技能要求",
                        value: skill
                    )
                }

                if !task.requirements.equipment.isEmpty {
                    requirementRow(
                        icon: "wrench.and.screwdriver",
                        title: "所需设备",
                        value: task.requirements.equipment.joined(separator: ", ")
                    )
                }

                if let notes = task.requirements.notes, !notes.isEmpty {
                    requirementRow(
                        icon: "note.text",
                        title: "备注",
                        value: notes
                    )
                }
            }
        }
    }

    private func requirementRow(icon: String, title: String, value: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .foregroundStyle(.secondary)
                .frame(width: 24)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.caption)
                    .foregroundStyle(.secondary)

                Text(value)
                    .font(.subheadline)
            }

            Spacer()
        }
    }

    private func locationSection(task: ClawTask) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("任务地点")
                .font(.headline)

            HStack(spacing: 12) {
                Image(systemName: "location.fill")
                    .foregroundStyle(.tint)

                VStack(alignment: .leading, spacing: 4) {
                    if let address = task.location.address {
                        Text(address)
                            .font(.subheadline)
                    } else {
                        Text("经度: \(task.location.longitude), 纬度: \(task.location.latitude)")
                            .font(.subheadline)
                    }

                    if let radius = task.location.radiusMeters {
                        Text("半径: \(radius) 米")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }

                Spacer()
            }
        }
    }

    private func rewardSection(task: ClawTask) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("任务奖励")
                .font(.headline)

            HStack(alignment: .center, spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("基础积分")
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    Text("\(task.reward.credits)")
                        .font(.title2)
                        .fontWeight(.bold)
                }

                Spacer()

                if task.reward.bonusMultiplier > 1.0 {
                    VStack(alignment: .trailing, spacing: 4) {
                        Text("加成倍数")
                            .font(.caption)
                            .foregroundStyle(.secondary)

                        Text("x\(task.reward.bonusMultiplier)")
                            .font(.subheadline)
                            .foregroundStyle(.tint)
                    }
                }

                Spacer()

                VStack(alignment: .trailing, spacing: 4) {
                    Text("总计")
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    Text("\(task.reward.totalCredits)")
                        .font(.title)
                        .fontWeight(.bold)
                        .foregroundStyle(.tint)
                }
            }
        }
    }

    private func progressSection(task: ClawTask) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("任务进度")
                .font(.headline)

            VStack(alignment: .leading, spacing: 8) {
                ProgressView(value: viewModel.progress)
                    .tint(Color.accentColor)

                HStack {
                    Text("\(Int(viewModel.progress * 100))%")
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    Spacer()

                    Text(viewModel.progressDescription)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    private func actionButtons(task: ClawTask) -> some View {
        VStack(spacing: 12) {
            switch task.status {
            case .available:
                acceptButton(taskId: task.id)

            case .assigned, .inProgress:
                VStack(spacing: 8) {
                    Button {
                        viewModel.updateProgress(0.75)
                    } label: {
                        Text("模拟进度更新")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)

                    if task.status == .inProgress || viewModel.progress >= 0.75 {
                        Button("完成任务") {
                            showCompletionAlert = true
                        }
                        .buttonStyle(.borderedProminent)
                        .frame(maxWidth: .infinity)
                        .disabled(viewModel.isLoading)
                    }
                }

            case .completed:
                Button("已完成", action: {})
                    .disabled(true)
                    .frame(maxWidth: .infinity)

            case .expired:
                Button("已过期", systemImage: "xmark")
                    .disabled(true)
                    .frame(maxWidth: .infinity)
            }
        }
    }

    private func acceptButton(taskId: String) -> some View {
        Button("接受任务") {
            Task {
                await acceptTask()
            }
        }
        .buttonStyle(.borderedProminent)
        .frame(maxWidth: .infinity)
        .disabled(viewModel.isLoading)
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

    private func loadTask() async {
        await viewModel.loadTask(taskId: taskId)
    }

    private func acceptTask() async {
        let success = await viewModel.acceptTask(taskId: taskId)
        if success {
            await loadTask()
        }
    }

    private func submitTask() async {
        let success = await viewModel.submitTask(taskId: taskId)
        if success {
            showSuccessAlert = true
        }
    }
}

// MARK: - Task Detail View Model

@MainActor
final class TaskDetailViewModel: ObservableObject {
    @Published var task: ClawTask?
    @Published var progress: Double = 0.0
    @Published var progressDescription: String = "未开始"
    @Published var isLoading: Bool = false
    @Published var errorMessage: String?

    private var completedRequirements: Set<String> = []

    func loadTask(taskId: String) async {
        isLoading = true
        defer { isLoading = false }

        do {
            let service = TaskMarketService.shared
            // Fetch from myTasks since fetchTask doesn't exist
            let tasks = try await service.fetchMyTasks()
            task = tasks.first(where: { $0.id == taskId })
            updateProgressDisplay()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func acceptTask(taskId: String) async -> Bool {
        isLoading = true
        defer { isLoading = false }

        do {
            let service = TaskMarketService.shared
            _ = try await service.acceptTask(taskId: taskId)
            return true
        } catch {
            errorMessage = error.localizedDescription
            return false
        }
    }

    func submitTask(taskId: String) async -> Bool {
        isLoading = true
        defer { isLoading = false }

        do {
            let service = TaskMarketService.shared
            let result = TaskResult(
                taskId: taskId,
                submittedAt: Date(),
                data: ["success": true, "completed_requirements": Array(completedRequirements)],
                notes: "任务已完成"
            )
            _ = try await service.submitTaskResult(taskId: taskId, result: result)
            progress = 1.0
            progressDescription = "已完成"
            return true
        } catch {
            errorMessage = error.localizedDescription
            return false
        }
    }

    func updateProgress(_ newProgress: Double) {
        progress = min(max(newProgress, 0.0), 1.0)
        updateProgressDisplay()
    }

    private func updateProgressDisplay() {
        if progress == 0.0 {
            progressDescription = "未开始"
        } else if progress < 0.25 {
            progressDescription = "进行中 - 准备"
        } else if progress < 0.5 {
            progressDescription = "进行中 - 执行"
        } else if progress < 0.75 {
            progressDescription = "进行中 - 检查"
        } else if progress < 1.0 {
            progressDescription = "即将完成"
        } else {
            progressDescription = "已完成"
        }
    }
}

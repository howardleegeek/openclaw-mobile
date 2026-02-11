//
//  TaskListView.swift
//  ClawPhones
//

import SwiftUI

struct TaskListView: View {
    @EnvironmentObject private var auth: AuthViewModel
    @StateObject private var viewModel = TaskListViewModel()

    @AppStorage("autoAcceptTasks") private var autoAcceptTasks: Bool = false

    @State private var selectedTab: TaskTab = .available
    @State private var selectedTaskRoute: TaskRoute?

    private enum TaskTab: String, CaseIterable {
        case available = "可接任务"
        case myTasks = "我的任务"
        case completed = "已完成"
    }

    private struct TaskRoute: Identifiable, Hashable {
        let id: String
    }

    private static let dateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = "M月d日 HH:mm"
        return formatter
    }()

    var body: some View {
        taskListContent
            .navigationTitle("任务")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Toggle("自动接单", isOn: $autoAcceptTasks)
                        .labelsHidden()
                }
            }
            .navigationDestination(item: $selectedTaskRoute) { route in
                TaskDetailView(taskId: route.id)
            }
            .task(id: auth.isAuthenticated) {
                if auth.isAuthenticated {
                    await loadTasks()
                } else {
                    viewModel.availableTasks = []
                    viewModel.myTasks = []
                    viewModel.completedTasks = []
                }
            }
            .refreshable {
                if auth.isAuthenticated {
                    await loadTasks()
                }
            }
            .overlay {
                if viewModel.isLoading && viewModel.availableTasks.isEmpty && viewModel.myTasks.isEmpty {
                    ProgressView()
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

    private var taskListContent: some View {
        VStack(spacing: 0) {
            Picker("", selection: $selectedTab) {
                ForEach(TaskTab.allCases, id: \.self) { tab in
                    Text(tab.rawValue).tag(tab)
                }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal)
            .padding(.vertical, 12)

            Divider()

            List {
                switch selectedTab {
                case .available:
                    availableTasksSection
                case .myTasks:
                    myTasksSection
                case .completed:
                    completedTasksSection
                }
            }
        }
    }

    private var availableTasksSection: some View {
        Group {
            if viewModel.availableTasks.isEmpty && !viewModel.isLoading {
                ContentUnavailableView("暂无可接任务", systemImage: "list.bag", description: Text("稍后再来查看新任务"))
            } else {
                ForEach(viewModel.availableTasks) { task in
                    taskCard(for: task)
                        .contentShape(Rectangle())
                        .onTapGesture {
                            selectedTaskRoute = TaskRoute(id: task.id)
                        }
                }
            }
        }
    }

    private var myTasksSection: some View {
        Group {
            if viewModel.myTasks.isEmpty && !viewModel.isLoading {
                ContentUnavailableView("暂无进行中任务", systemImage: "hourglass", description: Text("去可接任务页面接单吧"))
            } else {
                ForEach(viewModel.myTasks) { task in
                    taskCard(for: task)
                        .contentShape(Rectangle())
                        .onTapGesture {
                            selectedTaskRoute = TaskRoute(id: task.id)
                        }
                }
            }
        }
    }

    private var completedTasksSection: some View {
        Group {
            if viewModel.completedTasks.isEmpty && !viewModel.isLoading {
                ContentUnavailableView("暂无已完成任务", systemImage: "checkmark.circle", description: Text("完成任务后在这里查看"))
            } else {
                ForEach(viewModel.completedTasks) { task in
                    taskCard(for: task)
                        .contentShape(Rectangle())
                        .onTapGesture {
                            selectedTaskRoute = TaskRoute(id: task.id)
                        }
                }
            }
        }
    }

    private func taskCard(for task: ClawTask) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: task.taskType.icon)
                    .font(.title2)
                    .foregroundStyle(.tint)
                    .frame(width: 32, height: 32)

                VStack(alignment: .leading, spacing: 4) {
                    Text(task.title)
                        .font(.headline)

                    HStack(spacing: 8) {
                        Label(task.taskType.displayName, systemImage: task.taskType.icon)
                            .font(.caption)
                            .foregroundStyle(.secondary)

                        Spacer()

                        Text("+\(task.reward.totalCredits) 积分")
                            .font(.subheadline)
                            .foregroundStyle(.tint)
                            .fontWeight(.semibold)
                    }
                }
            }

            Text(task.description)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .lineLimit(2)

            HStack(spacing: 16) {
                Label("\(task.requirements.durationMinutes) 分钟", systemImage: "clock")
                    .font(.caption)
                    .foregroundStyle(.secondary)

                if let location = task.location.address {
                    Label(location, systemImage: "location")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }

            if task.status == .inProgress {
                ProgressView(value: viewModel.progress(for: task.id))
                    .tint(.tint)
            }
        }
        .padding(.vertical, 4)
    }

    private func loadTasks() async {
        await viewModel.loadTasks()
    }
}

// MARK: - Task List View Model

@MainActor
final class TaskListViewModel: ObservableObject {
    @Published var availableTasks: [ClawTask] = []
    @Published var myTasks: [ClawTask] = []
    @Published var completedTasks: [ClawTask] = []
    @Published var isLoading: Bool = false
    @Published var errorMessage: String?

    private var taskProgress: [String: Double] = [:]

    func loadTasks() async {
        isLoading = true
        defer { isLoading = false }

        do {
            let service = TaskMarketService.shared
            availableTasks = await service.fetchAvailableTasks()
            myTasks = await service.fetchUserTasks(status: .inProgress)
            completedTasks = await service.fetchUserTasks(status: .completed)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func progress(for taskId: String) -> Double {
        return taskProgress[taskId] ?? 0.0
    }

    func setProgress(_ progress: Double, for taskId: String) {
        taskProgress[taskId] = progress
    }
}

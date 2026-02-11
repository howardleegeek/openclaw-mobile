//
//  SettingsView.swift
//  ClawPhones
//

import SwiftUI
import UIKit

struct SettingsView: View {
    @EnvironmentObject private var auth: AuthViewModel
    @StateObject private var viewModel = SettingsViewModel()
    @AppStorage(BiometricAuthService.lockEnabledStorageKey) private var biometricLockEnabled: Bool = false

    @State private var showEditName: Bool = false
    @State private var draftName: String = ""

    @State private var showChangePassword: Bool = false
    @State private var oldPassword: String = ""
    @State private var newPassword: String = ""

    @State private var showConfirmDeleteAll: Bool = false
    @State private var showConfirmLogout: Bool = false
    @State private var exportShareItem: ExportShareItem?
    @State private var showDeleteAccountPrompt: Bool = false
    @State private var deleteAccountConfirmText: String = ""
    @State private var biometryInfo = BiometricAuthService.shared.currentBiometryInfo()

    var body: some View {
        Form {
            Section {
                Button {
                    draftName = viewModel.profile.name
                    showEditName = true
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: "person.crop.circle.fill")
                            .font(.system(size: 44))
                            .foregroundStyle(.tint)

                        VStack(alignment: .leading, spacing: 2) {
                            Text(viewModel.profile.name.isEmpty ? "未设置昵称" : viewModel.profile.name)
                                .font(.headline)

                            Text("点击编辑昵称")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }

                        Spacer()

                        Image(systemName: "chevron.right")
                            .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 4)
                }
                .buttonStyle(.plain)

                HStack {
                    Text("邮箱")
                    Spacer()
                    Text(viewModel.profile.email)
                        .foregroundStyle(.secondary)
                }
            }

            Section("计划") {
                NavigationLink {
                    PlanView(plan: viewModel.plan)
                } label: {
                    HStack {
                        Text("当前计划")
                        Spacer()
                        Text(viewModel.plan.tier.rawValue)
                            .foregroundStyle(.secondary)
                    }
                }
            }

            Section("AI 设置") {
                NavigationLink {
                    AIConfigView(viewModel: viewModel)
                } label: {
                    HStack {
                        Text("当前人设")
                        Spacer()
                        Text(personaSummary(viewModel.aiConfig.persona))
                            .foregroundStyle(.secondary)
                    }
                }
            }

            Section("语言") {
                Picker("语言", selection: languageBinding) {
                    ForEach(SettingsViewModel.Language.allCases) { lang in
                        Text(lang.displayName).tag(lang)
                    }
                }
            }

            if biometryInfo.isAvailable {
                Section("安全") {
                    Toggle("\(biometryInfo.displayName) 锁定", isOn: $biometricLockEnabled)

                    Text("开启后，应用启动和从后台返回时会要求验证身份。")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }

            Section("账号") {
                Button("修改密码") {
                    oldPassword = ""
                    newPassword = ""
                    showChangePassword = true
                }

                Button {
                    Task {
                        if let fileURL = await viewModel.exportMyData() {
                            exportShareItem = ExportShareItem(fileURL: fileURL)
                        }
                    }
                } label: {
                    HStack(spacing: 10) {
                        if viewModel.isExportingData {
                            ProgressView()
                                .controlSize(.small)
                        }
                        Text("导出我的数据")
                    }
                }
                .disabled(viewModel.isLoading)

                Button("清除所有对话", role: .destructive) {
                    showConfirmDeleteAll = true
                }

                Button("删除账户", role: .destructive) {
                    deleteAccountConfirmText = ""
                    showDeleteAccountPrompt = true
                }
                .foregroundStyle(.red)
                .alert("删除账户", isPresented: $showDeleteAccountPrompt) {
                    TextField("输入 DELETE 以确认", text: $deleteAccountConfirmText)
                        .textInputAutocapitalization(.characters)
                    Button("永久删除", role: .destructive) {
                        Task {
                            let deleted = await viewModel.deleteAccount()
                            if deleted {
                                auth.refreshAuthState()
                            }
                        }
                    }
                    .disabled(deleteAccountConfirmText != "DELETE" || viewModel.isLoading)
                    Button("取消", role: .cancel) {}
                } message: {
                    Text("此操作会永久删除账号和所有数据，且不可恢复。")
                }

                Button("退出登录", role: .destructive) {
                    showConfirmLogout = true
                }
                .foregroundStyle(.red)
            }
        }
        .navigationTitle("设置")
        .task(id: auth.isAuthenticated) {
            guard auth.isAuthenticated else {
                viewModel.errorMessage = nil
                viewModel.profile = .mock
                viewModel.plan = .mock
                viewModel.aiConfig = .mock
                return
            }

            await viewModel.loadProfile()
            await viewModel.loadPlan()
            await viewModel.loadAIConfig()
        }
        .onAppear {
            refreshBiometryInfo()
        }
        .overlay {
            if viewModel.isLoading {
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
        .confirmationDialog("确认清除所有对话？", isPresented: $showConfirmDeleteAll, titleVisibility: .visible) {
            Button("清除所有对话", role: .destructive) {
                Task { await viewModel.clearAllConversations() }
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("此操作会删除你账号下的所有对话记录。")
        }
        .confirmationDialog("确认退出登录？", isPresented: $showConfirmLogout, titleVisibility: .visible) {
            Button("退出登录", role: .destructive) {
                viewModel.logout()
                auth.refreshAuthState()
            }
            Button("取消", role: .cancel) {}
        }
        .sheet(isPresented: $showEditName) {
            NavigationStack {
                Form {
                    Section("昵称") {
                        TextField("输入昵称", text: $draftName)
                            .textInputAutocapitalization(.words)
                    }
                }
                .navigationTitle("编辑昵称")
                .toolbar {
                    ToolbarItem(placement: .navigationBarLeading) {
                        Button("取消") { showEditName = false }
                    }
                    ToolbarItem(placement: .navigationBarTrailing) {
                        Button("保存") {
                            Task {
                                await viewModel.updateName(name: draftName)
                                showEditName = false
                            }
                        }
                        .disabled(draftName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                }
            }
        }
        .sheet(isPresented: $showChangePassword) {
            NavigationStack {
                Form {
                    Section("当前密码") {
                        SecureField("旧密码", text: $oldPassword)
                            .textInputAutocapitalization(.never)
                    }
                    Section("新密码") {
                        SecureField("新密码 (至少 8 位)", text: $newPassword)
                            .textInputAutocapitalization(.never)
                        if !newPassword.isEmpty && newPassword.count < 8 {
                            Text("密码至少 8 位")
                                .font(.footnote)
                                .foregroundStyle(.red)
                        }
                    }
                }
                .navigationTitle("修改密码")
                .toolbar {
                    ToolbarItem(placement: .navigationBarLeading) {
                        Button("取消") { showChangePassword = false }
                    }
                    ToolbarItem(placement: .navigationBarTrailing) {
                        Button("保存") {
                            Task {
                                await viewModel.changePassword(oldPassword: oldPassword, newPassword: newPassword)
                            }
                        }
                        .disabled(viewModel.isLoading || oldPassword.isEmpty || newPassword.count < 8)
                    }
                }
                .alert("Password changed", isPresented: Binding(
                    get: { viewModel.passwordChangeSucceeded },
                    set: { newValue in
                        if !newValue {
                            viewModel.passwordChangeSucceeded = false
                        }
                    }
                )) {
                    Button("OK", role: .cancel) {
                        viewModel.passwordChangeSucceeded = false
                        showChangePassword = false
                    }
                }
            }
        }
        .sheet(item: $exportShareItem) { item in
            ActivityShareSheet(activityItems: [item.fileURL])
        }
    }

    private var languageBinding: Binding<SettingsViewModel.Language> {
        Binding(
            get: { viewModel.profile.language },
            set: { newValue in
                Task { await viewModel.updateLanguage(lang: newValue) }
            }
        )
    }

    private func personaSummary(_ persona: SettingsViewModel.Persona) -> String {
        // Settings summary without emoji prefix.
        switch persona {
        case .general: return "通用助手"
        case .coding: return "编程专家"
        case .writing: return "写作助手"
        case .translation: return "翻译官"
        case .custom: return "自定义"
        }
    }

    private func refreshBiometryInfo() {
        let info = BiometricAuthService.shared.currentBiometryInfo()
        biometryInfo = info

        if !info.isAvailable {
            biometricLockEnabled = false
        }
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

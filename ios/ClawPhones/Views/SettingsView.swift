//
//  SettingsView.swift
//  ClawPhones
//

import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var auth: AuthViewModel
    @StateObject private var viewModel = SettingsViewModel()

    @State private var showEditName: Bool = false
    @State private var draftName: String = ""

    @State private var showChangePassword: Bool = false
    @State private var oldPassword: String = ""
    @State private var newPassword: String = ""

    @State private var showConfirmDeleteAll: Bool = false
    @State private var showConfirmLogout: Bool = false

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

            Section("账号") {
                Button("修改密码") {
                    oldPassword = ""
                    newPassword = ""
                    showChangePassword = true
                }

                Button("清除所有对话", role: .destructive) {
                    showConfirmDeleteAll = true
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
}

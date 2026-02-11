//
//  CommunitySettingsView.swift
//  ClawPhones
//

import SwiftUI

struct CommunitySettingsView: View {
    let community: Community
    @State private var detail: CommunityDetail?
    @State private var showEditName = false
    @State private var draftName = ""
    @State private var draftDescription = ""
    @State private var showManageMembers = false
    @State private var showLeaveConfirm = false
    @State private var showDeleteConfirm = false
    @State private var deleteConfirmText = ""
    @State private var errorMessage: String?
    @State private var showingError = false
    @State private var alertNotifications = true
    @State private var isLoading = false

    var body: some View {
        Form {
            Section("基本信息") {
                Button {
                    draftName = detail?.name ?? community.name
                    draftDescription = detail?.description ?? community.description ?? ""
                    showEditName = true
                } label: {
                    HStack {
                        Text("社区名称")
                        Spacer()
                        Text(detail?.name ?? community.name)
                            .foregroundStyle(.secondary)
                        Image(systemName: "chevron.right")
                            .foregroundStyle(.secondary)
                            .font(.caption)
                    }
                }
                .disabled(!isOwner)

                if let description = detail?.description, !description.isEmpty {
                    HStack(alignment: .top, spacing: 12) {
                        Text("描述")
                            .padding(.top, 2)
                        Spacer()
                        Text(description)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.trailing)
                    }
                }
            }

            Section("成员管理") {
                NavigationLink {
                    CommunityMembersView(communityId: community.id)
                } label: {
                    HStack {
                        Label("管理成员", systemImage: "person.2")
                        Spacer()
                        Text("\(detail?.memberCount ?? community.memberCount)")
                            .foregroundStyle(.secondary)
                        Image(systemName: "chevron.right")
                            .foregroundStyle(.secondary)
                            .font(.caption)
                    }
                }
                .disabled(!canManageMembers)
            }

            Section("通知设置") {
                Toggle("告警通知", isOn: $alertNotifications)
                Text("接收社区内成员上报的实时告警通知")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            if isOwner {
                Section("危险操作") {
                    Button("删除社区", role: .destructive) {
                        deleteConfirmText = ""
                        showDeleteConfirm = true
                    }
                }
            }

            Section {
                Button("离开社区", role: .destructive) {
                    showLeaveConfirm = true
                }
                .foregroundStyle(.red)
            }
        }
        .navigationTitle("社区设置")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await loadDetail()
        }
        .sheet(isPresented: $showEditName) {
            EditCommunitySheet(
                communityId: community.id,
                name: $draftName,
                description: $draftDescription,
                isPresented: $showEditName
            ) {
                Task { await loadDetail() }
            }
        }
        .alert("错误", isPresented: $showingError) {
            Button("确定", role: .cancel) {}
        } message: {
            Text(errorMessage ?? "")
        }
        .alert("确认离开社区？", isPresented: $showLeaveConfirm, titleVisibility: .visible) {
            Button("离开", role: .destructive) {
                Task {
                    await leaveCommunity()
                }
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("离开后将不再接收该社区的任何通知和告警信息。")
        }
        .alert("删除社区", isPresented: $showDeleteConfirm, titleVisibility: .visible) {
            TextField("输入 DELETE 以确认", text: $deleteConfirmText)
                .textInputAutocapitalization(.characters)
            Button("永久删除", role: .destructive) {
                Task {
                    await deleteCommunity()
                }
            }
            .disabled(deleteConfirmText != "DELETE" || isLoading)
            Button("取消", role: .cancel) {}
        } message: {
            Text("此操作将永久删除社区及其所有数据，且不可恢复。")
        }
        .overlay {
            if isLoading {
                ProgressView()
            }
        }
    }

    private var isOwner: Bool {
        detail?.isOwner ?? community.isOwner
    }

    private var canManageMembers: Bool {
        isOwner
    }

    @MainActor
    private func loadDetail() async {
        detail = await CommunityService.shared.fetchCommunityDetail(id: community.id)
    }

    @MainActor
    private func leaveCommunity() async {
        isLoading = true
        let result = await CommunityService.shared.leaveCommunity(communityId: community.id)

        if case .failure(let error) = result {
            errorMessage = error.localizedDescription
            showingError = true
            isLoading = false
            return
        }

        isLoading = false
    }

    @MainActor
    private func deleteCommunity() async {
        isLoading = true
        let result = await CommunityService.shared.deleteCommunity(communityId: community.id)

        if case .failure(let error) = result {
            errorMessage = error.localizedDescription
            showingError = true
            isLoading = false
            return
        }

        isLoading = false
    }
}

extension CommunityService {
    func deleteCommunity(communityId: String) async -> Result<Void, CommunityError> {
        // TODO: Implement API call
        return .failure(.unknown("Not implemented"))
    }
}

private struct EditCommunitySheet: View {
    @Environment(\.dismiss) private var dismiss
    let communityId: String
    @Binding var name: String
    @Binding var description: String
    @Binding var isPresented: Bool
    let onSave: () -> Void

    @State private var isSaving = false
    @State private var errorMessage: String?
    @State private var showingError = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("社区名称", text: $name)
                        .textInputAutocapitalization(.words)

                    TextField("描述 (可选)", text: $description, axis: .vertical)
                        .lineLimit(3...6)
                }
            }
            .navigationTitle("编辑社区")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("取消") {
                        dismiss()
                    }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("保存") {
                        Task {
                            await saveChanges()
                        }
                    }
                    .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || isSaving)
                }
            }
            .alert("错误", isPresented: $showingError) {
                Button("确定", role: .cancel) {}
            } message: {
                Text(errorMessage ?? "")
            }
            .overlay {
                if isSaving {
                    ProgressView()
                }
            }
        }
    }

    @MainActor
    private func saveChanges() async {
        let communityName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !communityName.isEmpty else { return }

        isSaving = true
        defer { isSaving = false }

        let result = await CommunityService.shared.updateCommunity(
            id: communityId,
            name: communityName,
            description: description.trimmingCharacters(in: .whitespacesAndNewlines)
        )

        if case .failure(let error) = result {
            errorMessage = error.localizedDescription
            showingError = true
            return
        }

        dismiss()
        onSave()
    }
}

extension CommunityService {
    func updateCommunity(id: String, name: String, description: String) async -> Result<Void, CommunityError> {
        // TODO: Implement API call
        return .failure(.unknown("Not implemented"))
    }
}

private struct CommunityMembersView: View {
    let communityId: String
    @State private var members: [CommunityMember] = []
    @State private var isLoading = true
    @State private var showAddMember = false
    @State private var memberToRemove: CommunityMember?
    @State private var showRemoveConfirm = false
    @State private var errorMessage: String?
    @State private var showingError = false

    var body: some View {
        List {
            if isLoading && members.isEmpty {
                Section {
                    HStack {
                        Spacer()
                        ProgressView()
                        Spacer()
                    }
                }
            } else if members.isEmpty {
                Section {
                    ContentUnavailableView("暂无成员", systemImage: "person.3.slash")
                }
            } else {
                ForEach(members) { member in
                    MemberRow(member: member, canRemove: canRemove(member)) {
                        memberToRemove = member
                        showRemoveConfirm = true
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("成员管理")
        .navigationBarTitleDisplayMode(.large)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button {
                    showAddMember = true
                } label: {
                    Image(systemName: "plus")
                }
            }
        }
        .sheet(isPresented: $showAddMember) {
            AddMemberSheet(communityId: communityId, isPresented: $showAddMember) {
                Task { await loadMembers() }
            }
        }
        .alert("错误", isPresented: $showingError) {
            Button("确定", role: .cancel) {}
        } message: {
            Text(errorMessage ?? "")
        }
        .confirmationDialog("确认移除成员？", isPresented: $showRemoveConfirm, titleVisibility: .visible, presenting: memberToRemove) { member in
            Button("移除", role: .destructive) {
                Task {
                    await removeMember(member)
                }
            }
            Button("取消", role: .cancel) {}
        } message: { member in
            Text("确定要移除 \(member.name) 吗？")
        }
    }

    @MainActor
    private func loadMembers() async {
        isLoading = true
        if let detail = await CommunityService.shared.fetchCommunityDetail(id: communityId) {
            members = detail.members
        }
        isLoading = false
    }

    private func canRemove(_ member: CommunityMember) -> Bool {
        member.role != .owner
    }

    @MainActor
    private func removeMember(_ member: CommunityMember) async {
        let result = await CommunityService.shared.removeMember(
            communityId: communityId,
            memberId: member.id
        )

        if case .failure(let error) = result {
            errorMessage = error.localizedDescription
            showingError = true
            return
        }

        await loadMembers()
    }
}

extension CommunityService {
    func removeMember(communityId: String, memberId: String) async -> Result<Void, CommunityError> {
        // TODO: Implement API call
        return .failure(.unknown("Not implemented"))
    }
}

private struct MemberRow: View {
    let member: CommunityMember
    let canRemove: Bool
    let onRemove: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(colorForRole(member.role).opacity(0.15))

                Text(initials(from: member.name))
                    .font(.headline)
                    .foregroundStyle(colorForRole(member.role))
            }
            .frame(width: 48, height: 48)

            VStack(alignment: .leading, spacing: 2) {
                Text(member.name)
                    .font(.subheadline.weight(.medium))

                if let email = member.email {
                    Text(email)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Text(roleDisplayName(member.role))
                    .font(.caption2)
                    .foregroundStyle(colorForRole(member.role))
            }

            Spacer()

            if canRemove {
                Button(role: .destructive) {
                    onRemove()
                } label: {
                    Label("移除", systemImage: "trash")
                        .font(.caption)
                }
                .buttonStyle(.borderless)
            }
        }
    }

    private func initials(from name: String) -> String {
        let components = name.components(separatedBy: .whitespaces).filter { !$0.isEmpty }
        return components.prefix(2).map { String($0.prefix(1)) }.joined()
    }

    private func colorForRole(_ role: MemberRole) -> Color {
        switch role {
        case .owner: return .blue
        case .admin: return .orange
        case .member: return .gray
        }
    }

    private func roleDisplayName(_ role: MemberRole) -> String {
        switch role {
        case .owner: return "所有者"
        case .admin: return "管理员"
        case .member: return "成员"
        }
    }
}

private struct AddMemberSheet: View {
    @Environment(\.dismiss) private var dismiss
    let communityId: String
    @Binding var isPresented: Bool
    let onAdd: () -> Void

    @State private var email = ""
    @State private var role: MemberRole = .member
    @State private var isAdding = false
    @State private var errorMessage: String?
    @State private var showingError = false

    var body: some View {
        NavigationStack {
            Form {
                Section("邀请成员") {
                    TextField("邮箱地址", text: $email)
                        .textInputAutocapitalization(.never)
                        .keyboardType(.emailAddress)

                    Picker("角色", selection: $role) {
                        Text("成员").tag(MemberRole.member)
                        Text("管理员").tag(MemberRole.admin)
                    }
                }
            }
            .navigationTitle("添加成员")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("取消") {
                        dismiss()
                    }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("添加") {
                        Task {
                            await addMember()
                        }
                    }
                    .disabled(email.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || isAdding)
                }
            }
            .alert("错误", isPresented: $showingError) {
                Button("确定", role: .cancel) {}
            } message: {
                Text(errorMessage ?? "")
            }
            .overlay {
                if isAdding {
                    ProgressView()
                }
            }
        }
    }

    @MainActor
    private func addMember() async {
        let memberEmail = email.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !memberEmail.isEmpty else { return }

        isAdding = true
        defer { isAdding = false }

        let result = await CommunityService.shared.addMember(
            communityId: communityId,
            email: memberEmail,
            role: role
        )

        if case .failure(let error) = result {
            errorMessage = error.localizedDescription
            showingError = true
            return
        }

        dismiss()
        onAdd()
    }
}

extension CommunityService {
    func addMember(communityId: String, email: String, role: MemberRole) async -> Result<Void, CommunityError> {
        // TODO: Implement API call
        return .failure(.unknown("Not implemented"))
    }
}

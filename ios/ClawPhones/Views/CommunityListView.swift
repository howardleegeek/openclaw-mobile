//
//  CommunityListView.swift
//  ClawPhones
//

import SwiftUI

struct CommunityListView: View {
    @State private var communities: [Community] = []
    @State private var isLoading = true
    @State private var showCreateSheet = false
    @State private var showJoinDialog = false
    @State private var joinCode = ""
    @State private var errorMessage: String?
    @State private var showingError = false

    private static let dateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        return formatter
    }()

    var body: some View {
        List {
            if isLoading && communities.isEmpty {
                Section {
                    HStack {
                        Spacer()
                        ProgressView()
                        Spacer()
                    }
                }
            } else if communities.isEmpty {
                Section {
                    VStack(spacing: 16) {
                        Image(systemName: "person.3.fill")
                            .font(.system(size: 48))
                            .foregroundStyle(.secondary)

                        Text("暂无社区")
                            .font(.headline)
                            .foregroundStyle(.secondary)

                        Text("创建或加入一个社区，与好友共享实时告警")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)

                        Button("创建社区") {
                            showCreateSheet = true
                        }
                        .buttonStyle(.borderedProminent)

                        Button("加入社区") {
                            showJoinDialog = true
                        }
                        .buttonStyle(.bordered)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 32)
                }
            } else {
                ForEach(communities) { community in
                    NavigationLink {
                        CommunityDetailView(community: community)
                    } label: {
                        communityRow(community)
                    }
                }
                .onDelete { indexSet in
                    for index in indexSet {
                        Task {
                            await leaveCommunity(communities[index])
                        }
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("社区")
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Menu {
                    Button {
                        showCreateSheet = true
                    } label: {
                        Label("创建社区", systemImage: "plus")
                    }

                    Button {
                        showJoinDialog = true
                    } label: {
                        Label("加入社区", systemImage: "link")
                    }
                } label: {
                    Image(systemName: "plus")
                }
            }
        }
        .task {
            await loadCommunities()
        }
        .refreshable {
            await loadCommunities()
        }
        .sheet(isPresented: $showCreateSheet) {
            CreateCommunitySheet(isPresented: $showCreateSheet) {
                Task { await loadCommunities() }
            }
        }
        .alert("加入社区", isPresented: $showJoinDialog) {
            TextField("输入邀请码", text: $joinCode)
            Button("取消", role: .cancel) {
                joinCode = ""
            }
            Button("加入") {
                Task {
                    await joinWithCode()
                }
            }
            .disabled(joinCode.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        } message: {
            Text("请输入社区邀请码以加入该社区")
        }
        .alert("错误", isPresented: $showingError) {
            Button("确定", role: .cancel) {}
        } message: {
            Text(errorMessage ?? "")
        }
    }

    private func communityRow(_ community: Community) -> some View {
        HStack(spacing: 12) {
            ZStack {
                Color(uiColor: .secondarySystemBackground)
                Image(systemName: "person.3.fill")
                    .font(.title2)
                    .foregroundStyle(.tint)
            }
            .frame(width: 56, height: 56)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))

            VStack(alignment: .leading, spacing: 4) {
                Text(community.name)
                    .font(.headline)
                    .foregroundStyle(.primary)

                if let description = community.description, !description.isEmpty {
                    Text(description)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }

                HStack(spacing: 8) {
                    Label("\(community.memberCount) 成员", systemImage: "person.2")
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    if let createdAt = community.createdAt {
                        Text("• \(Self.dateFormatter.string(from: createdAt))")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }

            Spacer(minLength: 0)
        }
        .padding(.vertical, 4)
    }

    @MainActor
    private func loadCommunities() async {
        isLoading = true
        communities = await CommunityService.shared.fetchMyCommunities()
        isLoading = false
    }

    @MainActor
    private func joinWithCode() async {
        let code = joinCode.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !code.isEmpty else { return }

        let result = await CommunityService.shared.joinCommunity(inviteCode: code)
        if case .failure(let error) = result {
            errorMessage = error.localizedDescription
            showingError = true
            return
        }

        joinCode = ""
        showJoinDialog = false
        await loadCommunities()
    }

    @MainActor
    private func leaveCommunity(_ community: Community) async {
        let result = await CommunityService.shared.leaveCommunity(communityId: community.id)
        if case .failure(let error) = result {
            errorMessage = error.localizedDescription
            showingError = true
            return
        }

        if let index = communities.firstIndex(where: { $0.id == community.id }) {
            communities.remove(at: index)
        }
    }
}

private struct CreateCommunitySheet: View {
    @Environment(\.dismiss) private var dismiss
    @Binding var isPresented: Bool
    let onCreate: () -> Void

    @State private var name = ""
    @State private var description = ""
    @State private var isCreating = false
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
            .navigationTitle("创建社区")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("取消") {
                        dismiss()
                    }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("创建") {
                        Task {
                            await createCommunity()
                        }
                    }
                    .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || isCreating)
                }
            }
            .alert("错误", isPresented: $showingError) {
                Button("确定", role: .cancel) {}
            } message: {
                Text(errorMessage ?? "")
            }
            .overlay {
                if isCreating {
                    ProgressView()
                }
            }
        }
    }

    @MainActor
    private func createCommunity() async {
        let communityName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !communityName.isEmpty else { return }

        isCreating = true
        defer { isCreating = false }

        let result = await CommunityService.shared.createCommunity(
            name: communityName,
            description: description.trimmingCharacters(in: .whitespacesAndNewlines)
        )

        if case .failure(let error) = result {
            errorMessage = error.localizedDescription
            showingError = true
            return
        }

        dismiss()
        onCreate()
    }
}

// MARK: - Community Model

struct Community: Identifiable, Codable {
    let id: String
    let name: String
    let description: String?
    let memberCount: Int
    let createdAt: Date?
    let isOwner: Bool

    enum CodingKeys: String, CodingKey {
        case id
        case name
        case description
        case memberCount = "member_count"
        case createdAt = "created_at"
        case isOwner = "is_owner"
    }
}

// MARK: - Community Service

enum CommunityError: LocalizedError {
    case invalidInviteCode
    case communityNotFound
    case alreadyMember
    case permissionDenied
    case networkError(Error)
    case unknown(String)

    var errorDescription: String? {
        switch self {
        case .invalidInviteCode:
            return "无效的邀请码"
        case .communityNotFound:
            return "社区不存在"
        case .alreadyMember:
            return "你已经是该社区的成员"
        case .permissionDenied:
            return "没有权限执行此操作"
        case .networkError(let error):
            return "网络错误: \(error.localizedDescription)"
        case .unknown(let message):
            return message
        }
    }
}

actor CommunityService {
    static let shared = CommunityService()

    private init() {}

    func fetchMyCommunities() async -> [Community] {
        // TODO: Implement API call
        return []
    }

    func createCommunity(name: String, description: String) async -> Result<Community, CommunityError> {
        // TODO: Implement API call
        return .failure(.unknown("Not implemented"))
    }

    func joinCommunity(inviteCode: String) async -> Result<Void, CommunityError> {
        // TODO: Implement API call
        return .failure(.unknown("Not implemented"))
    }

    func leaveCommunity(communityId: String) async -> Result<Void, CommunityError> {
        // TODO: Implement API call
        return .failure(.unknown("Not implemented"))
    }

    func fetchCommunityDetail(id: String) async -> CommunityDetail? {
        // TODO: Implement API call
        return nil
    }
}

struct CommunityDetail: Identifiable {
    let id: String
    let name: String
    let description: String?
    let memberCount: Int
    let coverageArea: String?
    let createdAt: Date?
    let isOwner: Bool
    let inviteCode: String
    let alerts: [CommunityAlert]
    let members: [CommunityMember]
}

struct CommunityAlert: Identifiable, Codable {
    let id: String
    let communityId: String
    let type: String
    let confidence: Float
    let latitude: Double
    let longitude: Double
    let timestamp: Date
    let thumbnailData: Data?
    let reportedBy: String
    let reporterName: String?

    var typeIconName: String {
        switch type.lowercased() {
        case "fire", "smoke": return "flame.fill"
        case "intruder", "person": return "person.fill"
        case "accident", "collision": return "car.fill"
        default: return "exclamationmark.triangle.fill"
        }
    }

    var displayType: String {
        switch type.lowercased() {
        case "fire", "smoke": return "火灾/烟雾"
        case "intruder", "person": return "入侵检测"
        case "accident", "collision": return "交通事故"
        default: return type
        }
    }
}

struct CommunityMember: Identifiable, Codable {
    let id: String
    let name: String
    let email: String?
    let role: MemberRole
    let joinedAt: Date?
}

enum MemberRole: String, Codable {
    case owner = "owner"
    case admin = "admin"
    case member = "member"
}

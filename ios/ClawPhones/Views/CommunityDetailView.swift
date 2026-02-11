//
//  CommunityDetailView.swift
//  ClawPhones
//

import SwiftUI
import UIKit

struct CommunityDetailView: View {
    let community: Community
    @State private var communityDetail: CommunityDetail?
    @State private var isLoading = true
    @State private var errorMessage: String?
    @State private var showingError = false
    @State private var shareInviteCode = ""
    @State private var showShareSheet = false

    private static let dateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        return formatter
    }()

    private static let timeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = "HH:mm"
        return formatter
    }()

    var body: some View {
        Group {
            if isLoading && communityDetail == nil {
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if let detail = communityDetail {
                ScrollView {
                    VStack(spacing: 0) {
                        headerCard(detail: detail)
                            .padding()

                        alertsSection(detail: detail)
                            .padding(.horizontal)

                        membersSection(detail: detail)
                            .padding(.horizontal, 16)
                            .padding(.bottom, 8)
                    }
                }
                .background(Color(uiColor: .systemGroupedBackground))
                .navigationTitle(detail.name)
                .navigationBarTitleDisplayMode(.large)
                .toolbar {
                    ToolbarItem(placement: .navigationBarTrailing) {
                        Button {
                            shareInviteCode = detail.inviteCode
                            showShareSheet = true
                        } label: {
                            Label("邀请", systemImage: "square.and.arrow.up")
                        }
                    }
                }
                .sheet(isPresented: $showShareSheet) {
                    ActivityShareSheet(activityItems: ["加入我的社区，邀请码: \(shareInviteCode)"])
                }
            } else {
                ContentUnavailableView("无法加载社区信息", systemImage: "exclamationmark.triangle")
            }
        }
        .task {
            await loadDetail()
        }
        .alert("错误", isPresented: $showingError) {
            Button("确定", role: .cancel) {}
        } message: {
            Text(errorMessage ?? "")
        }
    }

    private func headerCard(detail: CommunityDetail) -> some View {
        VStack(spacing: 16) {
            HStack(spacing: 16) {
                ZStack {
                    LinearGradient(
                        colors: [.blue.opacity(0.2), .purple.opacity(0.2)],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                    Image(systemName: "person.3.fill")
                        .font(.system(size: 36))
                        .foregroundStyle(.tint)
                }
                .frame(width: 80, height: 80)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))

                VStack(alignment: .leading, spacing: 6) {
                    Text(detail.name)
                        .font(.title2.weight(.semibold))

                    if let description = detail.description, !description.isEmpty {
                        Text(description)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }

                    if let coverageArea = detail.coverageArea, !coverageArea.isEmpty {
                        HStack(spacing: 6) {
                            Image(systemName: "map.fill")
                                .font(.caption)
                            Text(coverageArea)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }

            Divider()

            HStack(spacing: 0) {
                StatItem(
                    icon: "person.2.fill",
                    label: "成员",
                    value: "\(detail.memberCount)"
                )

                Divider()
                    .frame(height: 40)

                StatItem(
                    icon: "exclamationmark.triangle.fill",
                    label: "今日告警",
                    value: "\(todayAlertCount(detail.alerts))"
                )

                Divider()
                    .frame(height: 40)

                StatItem(
                    icon: "bell.fill",
                    label: "活跃节点",
                    value: "\(activeNodeCount(detail.alerts))"
                )
            }
        }
        .padding(20)
        .background(Color(uiColor: .secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private func alertsSection(detail: CommunityDetail) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("实时告警")
                    .font(.headline)

                Spacer()

                NavigationLink {
                    CommunityAlertsView(communityId: detail.id)
                } label: {
                    Text("查看全部")
                        .font(.subheadline)
                        .foregroundStyle(.tint)
                }
            }

            if detail.alerts.isEmpty {
                VStack(spacing: 12) {
                    Image(systemName: "bell.slash.fill")
                        .font(.system(size: 40))
                        .foregroundStyle(.secondary)

                    Text("暂无告警")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 32)
                .background(Color(uiColor: .secondarySystemGroupedBackground))
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            } else {
                LazyVStack(spacing: 8) {
                    ForEach(detail.alerts.prefix(5)) { alert in
                        AlertRow(alert: alert)
                    }
                }
            }
        }
        .padding(.top, 8)
    }

    private func membersSection(detail: CommunityDetail) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("社区成员")
                    .font(.headline)

                if detail.isOwner {
                    NavigationLink(destination: CommunitySettingsView(community: detail.community)) {
                        Text("管理")
                            .font(.subheadline)
                            .foregroundStyle(.tint)
                    }
                }
            }

            if detail.members.isEmpty {
                VStack(spacing: 12) {
                    Image(systemName: "person.3.slash")
                        .font(.system(size: 32))
                        .foregroundStyle(.secondary)

                    Text("暂无成员")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 24)
                .background(Color(uiColor: .secondarySystemGroupedBackground))
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            } else {
                LazyVGrid(columns: [
                    GridItem(.flexible()),
                    GridItem(.flexible()),
                    GridItem(.flexible()),
                    GridItem(.flexible())
                ], spacing: 12) {
                    ForEach(detail.members.prefix(8)) { member in
                        MemberAvatar(member: member)
                    }
                }

                if detail.members.count > 8 {
                    HStack {
                        Spacer()
                        Text("还有 \(detail.members.count - 8) 位成员")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Spacer()
                    }
                }
            }
        }
        .padding(.top, 8)
    }

    @MainActor
    private func loadDetail() async {
        isLoading = true
        communityDetail = await CommunityService.shared.fetchCommunityDetail(id: community.id)
        isLoading = false

        if communityDetail == nil {
            errorMessage = "无法加载社区详情"
            showingError = true
        }
    }

    private func todayAlertCount(_ alerts: [CommunityAlert]) -> Int {
        let calendar = Calendar.current
        return alerts.filter { calendar.isDateInToday($0.timestamp) }.count
    }

    private func activeNodeCount(_ alerts: [CommunityAlert]) -> Int {
        let calendar = Calendar.current
        let oneHourAgo = calendar.date(byAdding: .hour, value: -1, to: Date()) ?? Date()
        return Set(alerts.filter { $0.timestamp >= oneHourAgo }.map { $0.reportedBy }).count
    }
}

private struct StatItem: View {
    let icon: String
    let label: String
    let value: String

    var body: some View {
        VStack(spacing: 4) {
            Image(systemName: icon)
                .foregroundStyle(.tint)

            Text(value)
                .font(.headline)

            Text(label)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
    }
}

private struct AlertRow: View {
    let alert: CommunityAlert

    private static let dateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = "HH:mm"
        return formatter
    }()

    var body: some View {
        HStack(spacing: 12) {
            Group {
                if let data = alert.thumbnailData, let image = UIImage(data: data) {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                } else {
                    ZStack {
                        Color(uiColor: .secondarySystemBackground)
                        Image(systemName: alert.typeIconName)
                            .foregroundStyle(.orange)
                    }
                }
            }
            .frame(width: 72, height: 54)
            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))

            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Image(systemName: alert.typeIconName)
                        .foregroundStyle(.orange)
                    Text(alert.displayType)
                        .font(.subheadline.weight(.semibold))
                }

                Text(Self.dateFormatter.string(from: alert.timestamp))
                    .font(.caption)
                    .foregroundStyle(.secondary)

                Text("置信度 \(Int((alert.confidence * 100).rounded()))%")
                    .font(.caption)
                    .foregroundStyle(.secondary)

                if let reporterName = alert.reporterName {
                    Text("上报: \(reporterName)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            Spacer(minLength: 0)
        }
        .padding(12)
        .background(Color(uiColor: .secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

private struct MemberAvatar: View {
    let member: CommunityMember

    var body: some View {
        VStack(spacing: 6) {
            ZStack {
                Circle()
                    .fill(colorForRole(member.role).opacity(0.15))

                Text(initials(from: member.name))
                    .font(.headline)
                    .foregroundStyle(colorForRole(member.role))
            }
            .frame(width: 52, height: 52)

            Text(member.name)
                .font(.caption2)
                .foregroundStyle(.primary)
                .lineLimit(1)

            Text(roleDisplayName(member.role))
                .font(.caption2)
                .foregroundStyle(.secondary)
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

private struct CommunityAlertsView: View {
    let communityId: String
    @State private var alerts: [CommunityAlert] = []
    @State private var isLoading = true

    var body: some View {
        List {
            if isLoading && alerts.isEmpty {
                Section {
                    HStack {
                        Spacer()
                        ProgressView()
                        Spacer()
                    }
                }
            } else if alerts.isEmpty {
                Section {
                    ContentUnavailableView("暂无告警记录", systemImage: "bell.slash")
                }
            } else {
                ForEach(alerts) { alert in
                    AlertRow(alert: alert)
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("告警记录")
        .task {
            await loadAlerts()
        }
    }

    @MainActor
    private func loadAlerts() async {
        isLoading = true
        // TODO: Fetch alerts from API
        alerts = []
        isLoading = false
    }
}

private extension CommunityDetail {
    var community: Community {
        Community(
            id: id,
            name: name,
            description: description,
            memberCount: memberCount,
            createdAt: createdAt,
            isOwner: isOwner
        )
    }
}

private struct ActivityShareSheet: UIViewControllerRepresentable {
    let activityItems: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: activityItems, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

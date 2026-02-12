//
//  LeaderboardView.swift
//  ClawPhones
//
//  Created on 2026-02-11.
//

import SwiftUI

struct LeaderboardView: View {
    @EnvironmentObject private var auth: AuthViewModel
    @StateObject private var viewModel = LeaderboardViewModel()

    var body: some View {
        ZStack(alignment: .bottom) {
            ScrollView {
                if let leaderboard = viewModel.leaderboard {
                    leaderboardContent(leaderboard: leaderboard)
                } else if viewModel.isLoading {
                    progressView
                } else if viewModel.errorMessage != nil {
                    errorView
                }
            }
            .navigationTitle("排行榜")
            .task(id: auth.isAuthenticated) {
                if auth.isAuthenticated {
                    await loadLeaderboard()
                }
            }
            .refreshable {
                if auth.isAuthenticated {
                    await loadLeaderboard()
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
            .sheet(isPresented: $viewModel.showBadgeExplanation) {
                badgeExplanationSheet
            }

            // My rank card pinned at bottom
            if let myRank = viewModel.myRank {
                myRankCard(myRank: myRank)
            }
        }
    }

    private func leaderboardContent(leaderboard: Leaderboard) -> some View {
        VStack(spacing: 20) {
            timePeriodPicker

            podiumSection(topUsers: leaderboard.topUsers)

            Divider()
                .padding(.horizontal)

            rankedListSection(users: leaderboard.users)
        }
        .padding()
        .padding(.bottom, 80)
    }

    // MARK: - Time Period Picker

    private var timePeriodPicker: some View {
        Picker("时间范围", selection: $viewModel.timePeriod) {
            Text("今日").tag(LeaderboardTimePeriod.daily)
            Text("本周").tag(LeaderboardTimePeriod.weekly)
            Text("本月").tag(LeaderboardTimePeriod.monthly)
            Text("总榜").tag(LeaderboardTimePeriod.allTime)
        }
        .pickerStyle(.segmented)
    }

    // MARK: - Podium Section

    private func podiumSection(topUsers: [LeaderboardUser]) -> some View {
        HStack(alignment: .bottom, spacing: 12) {
            // Second place
            if topUsers.count > 1 {
                podiumCard(
                    user: topUsers[1],
                    rank: 2,
                    height: 100,
                    badgeColor: .blue
                )
            }

            // First place
            if !topUsers.isEmpty {
                podiumCard(
                    user: topUsers[0],
                    rank: 1,
                    height: 120,
                    badgeColor: .yellow
                )
            }

            // Third place
            if topUsers.count > 2 {
                podiumCard(
                    user: topUsers[2],
                    rank: 3,
                    height: 80,
                    badgeColor: .orange
                )
            }
        }
        .frame(height: 160)
    }

    private func podiumCard(user: LeaderboardUser, rank: Int, height: CGFloat, badgeColor: Color) -> some View {
        VStack(spacing: 8) {
            // Rank badge with icon
            ZStack {
                Circle()
                    .fill(badgeColor.opacity(0.2))
                    .frame(width: rank == 1 ? 70 : 60, height: rank == 1 ? 70 : 60)

                Circle()
                    .fill(badgeColor)
                    .frame(width: rank == 1 ? 64 : 54, height: rank == 1 ? 64 : 54)

                if rank == 1 {
                    Text("👑")
                        .font(.system(size: 24))
                } else {
                    Text("\(rank)")
                        .font(.system(size: 20, weight: .bold))
                        .foregroundStyle(.white)
                }
            }

            VStack(spacing: 2) {
                Text(user.avatarEmoji)
                    .font(.title3)

                Text(user.username)
                    .font(.caption)
                    .fontWeight(.medium)
                    .lineLimit(1)

                Text("\(user.score)")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            // Podium platform
            RoundedRectangle(cornerRadius: 8)
                .fill(badgeColor.opacity(0.3))
                .frame(width: 70, height: height)
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: - Ranked List Section

    private func rankedListSection(users: [LeaderboardUser]) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("排行榜")
                    .font(.headline)

                Spacer()

                Text("共 \(users.count) 人")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            if users.isEmpty {
                Text("暂无排名数据")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .center)
                    .padding(.vertical, 32)
            } else {
                VStack(spacing: 0) {
                    ForEach(users) { user in
                        if user.rank <= 3 {
                            // Skip top 3 (shown in podium)
                            EmptyView()
                        } else {
                            rankedRow(user: user)

                            if user.id != users.last?.id {
                                Divider()
                                    .padding(.leading, 60)
                            }
                        }
                    }
                }
            }
        }
    }

    private func rankedRow(user: LeaderboardUser) -> some View {
        HStack(spacing: 12) {
            // Rank number
            Text("\(user.rank)")
                .font(.headline)
                .foregroundStyle(.secondary)
                .frame(width: 32, alignment: .leading)

            // Avatar
            Text(user.avatarEmoji)
                .font(.title2)

            // User info
            VStack(alignment: .leading, spacing: 2) {
                Text(user.username)
                    .font(.subheadline)
                    .fontWeight(.medium)

                if let badge = user.badge {
                    badgeBadge(badge: badge)
                }
            }

            Spacer()

            // Score
            VStack(alignment: .trailing, spacing: 2) {
                Text("\(user.score)")
                    .font(.headline)
                    .foregroundStyle(.tint)
                    .fontWeight(.semibold)

                if user.scoreChange != 0 {
                    scoreChangeBadge(change: user.scoreChange)
                }
            }
        }
        .padding(.vertical, 10)
    }

    private func badgeBadge(badge: Badge) -> some View {
        HStack(spacing: 4) {
            Text(badge.emoji)
                .font(.caption2)

            Text(badge.name)
                .font(.caption2)
                .foregroundStyle(badge.rarity.color)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(
            Capsule()
                .fill(badge.rarity.color.opacity(0.15))
        )
    }

    private func scoreChangeBadge(change: Int) -> some View {
        HStack(spacing: 2) {
            Image(systemName: change > 0 ? "arrow.up" : "arrow.down")
                .font(.caption2)

            Text(abs(change))
                .font(.caption2)
        }
        .foregroundStyle(change > 0 ? .green : .red)
    }

    // MARK: - My Rank Card

    private func myRankCard(myRank: LeaderboardUser) -> some View {
        HStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(Color.tint.opacity(0.2))
                    .frame(width: 40, height: 40)

                Text("\(myRank.rank)")
                    .font(.headline)
                    .foregroundStyle(.tint)
            }

            Text(myRank.avatarEmoji)
                .font(.title2)

            VStack(alignment: .leading, spacing: 2) {
                Text(myRank.username)
                    .font(.subheadline)
                    .fontWeight(.semibold)

                Text("我的排名")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Spacer()

            VStack(alignment: .trailing, spacing: 2) {
                Text("\(myRank.score)")
                    .font(.headline)
                    .foregroundStyle(.tint)
                    .fontWeight(.semibold)

                if myRank.scoreChange != 0 {
                    scoreChangeBadge(change: myRank.scoreChange)
                }
            }

            Button(action: {
                viewModel.showBadgeExplanation = true
            }) {
                Image(systemName: "info.circle")
                    .font(.title3)
                    .foregroundStyle(.secondary)
            }
        }
        .padding()
        .background(
            RoundedRectangle(cornerRadius: 16)
                .fill(Color(.secondarySystemBackground))
                .shadow(color: Color.black.opacity(0.1), radius: 8, x: 0, y: -4)
        )
        .padding(.horizontal)
        .padding(.bottom, 20)
    }

    // MARK: - Badge Explanation Sheet

    private var badgeExplanationSheet: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 24) {
                Text("徽章说明")
                    .font(.title2)
                    .fontWeight(.bold)

                VStack(spacing: 16) {
                    badgeExplanationRow(
                        badge: Badge(
                            id: "common",
                            name: "普通徽章",
                            description: "完成基础任务可获得",
                            emoji: "🔵",
                            rarity: .common,
                            requirements: [],
                            unlockedAt: nil
                        )
                    )

                    badgeExplanationRow(
                        badge: Badge(
                            id: "rare",
                            name: "稀有徽章",
                            description: "完成特定成就可获得",
                            emoji: "💎",
                            rarity: .rare,
                            requirements: [],
                            unlockedAt: nil
                        )
                    )

                    badgeExplanationRow(
                        badge: Badge(
                            id: "epic",
                            name: "史诗徽章",
                            description: "达到高级成就可获得",
                            emoji: "⭐",
                            rarity: .epic,
                            requirements: [],
                            unlockedAt: nil
                        )
                    )

                    badgeExplanationRow(
                        badge: Badge(
                            id: "legendary",
                            name: "传说徽章",
                            description: "顶级成就专属徽章",
                            emoji: "👑",
                            rarity: .legendary,
                            requirements: [],
                            unlockedAt: nil
                        )
                    )
                }

                Spacer()

                Text("徽章将在排行榜上显示，向其他用户展示您的成就！")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .padding()
            .navigationTitle("徽章")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") {
                        viewModel.showBadgeExplanation = false
                    }
                }
            }
        }
    }

    private func badgeExplanationRow(badge: Badge) -> some View {
        HStack(spacing: 12) {
            Text(badge.emoji)
                .font(.title2)

            VStack(alignment: .leading, spacing: 4) {
                Text(badge.name)
                    .font(.subheadline)
                    .fontWeight(.semibold)

                Text(badge.description)
                    .font(.caption)
                    .foregroundStyle(.secondary)

                HStack(spacing: 4) {
                    Circle()
                        .fill(badge.rarity.color)
                        .frame(width: 8, height: 8)

                    Text(badge.rarity.displayName)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }

            Spacer()
        }
        .padding()
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(Color(.secondarySystemBackground))
        )
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

    private func loadLeaderboard() async {
        await viewModel.loadLeaderboard()
    }
}

// MARK: - Leaderboard Time Period

enum LeaderboardTimePeriod: String, CaseIterable {
    case daily = "daily"
    case weekly = "weekly"
    case monthly = "monthly"
    case allTime = "all_time"

    var displayName: String {
        switch self {
        case .daily: return "今日"
        case .weekly: return "本周"
        case .monthly: return "本月"
        case .allTime: return "总榜"
        }
    }
}

// MARK: - Leaderboard User

struct LeaderboardUser: Identifiable, Codable {
    let id: String
    let username: String
    let avatarEmoji: String
    let score: Int
    let rank: Int
    let scoreChange: Int
    let badge: RewardBadge?
    let completedTasks: Int

    enum CodingKeys: String, CodingKey {
        case id
        case username
        case avatarEmoji = "avatar_emoji"
        case score
        case rank
        case scoreChange = "score_change"
        case badge
        case completedTasks = "completed_tasks"
    }
}

// MARK: - Leaderboard
// Leaderboard is defined in Models/TokenEconomy.swift

// MARK: - Leaderboard View Model

@MainActor
final class LeaderboardViewModel: ObservableObject {
    @Published var leaderboard: Leaderboard?
    @Published var myRank: LeaderboardUser?
    @Published var isLoading: Bool = false
    @Published var errorMessage: String?
    @Published var timePeriod: LeaderboardTimePeriod = .daily
    @Published var showBadgeExplanation: Bool = false

    func loadLeaderboard() async {
        isLoading = true
        defer { isLoading = false }

        do {
            try await Task.sleep(nanoseconds: 500_000_000)

            let mockUsers = generateMockUsers()
            let sortedUsers = mockUsers.sorted { $0.score > $1.score }

            leaderboard = Leaderboard(
                users: Array(sortedUsers.dropFirst(3)),
                topUsers: Array(sortedUsers.prefix(3)),
                timePeriod: timePeriod,
                lastUpdated: Date()
            )

            // Find current user's rank
            myRank = generateMockMyRank(userCount: sortedUsers.count)
        } catch {
            errorMessage = "加载排行榜失败"
        }
    }
}

extension LeaderboardViewModel {
    private func generateMockUsers() -> [LeaderboardUser] {
        let badges = [
            Badge(
                id: "badge1",
                name: "连续签到达人",
                description: "连续签到30天",
                emoji: "🔥",
                rarity: .epic,
                requirements: ["连续签到30天"],
                unlockedAt: Date()
            ),
            Badge(
                id: "badge2",
                name: "任务快手",
                description: "今日完成10个任务",
                emoji: "⚡",
                rarity: .rare,
                requirements: ["今日完成10个任务"],
                unlockedAt: Date()
            ),
            Badge(
                id: "badge3",
                name: "贡献之星",
                description: "累计贡献5000积分",
                emoji: "🌟",
                rarity: .common,
                requirements: ["累计贡献5000积分"],
                unlockedAt: Date()
            )
        ]

        return [
            LeaderboardUser(
                id: "1",
                username: "alex_wang",
                avatarEmoji: "👨‍💻",
                score: 2850,
                rank: 1,
                scoreChange: 0,
                badge: badges[0],
                completedTasks: 142
            ),
            LeaderboardUser(
                id: "2",
                username: "sarah_chen",
                avatarEmoji: "👩‍🔬",
                score: 2620,
                rank: 2,
                scoreChange: 2,
                badge: badges[1],
                completedTasks: 128
            ),
            LeaderboardUser(
                id: "3",
                username: "mike_liu",
                avatarEmoji: "🧑‍🎨",
                score: 2390,
                rank: 3,
                scoreChange: -1,
                badge: badges[2],
                completedTasks: 115
            ),
            LeaderboardUser(
                id: "4",
                username: "emma_zhang",
                avatarEmoji: "👩‍💼",
                score: 2180,
                rank: 4,
                scoreChange: 1,
                badge: nil,
                completedTasks: 98
            ),
            LeaderboardUser(
                id: "5",
                username: "david_wu",
                avatarEmoji: "👨‍🚀",
                score: 1950,
                rank: 5,
                scoreChange: 0,
                badge: badges[2],
                completedTasks: 87
            ),
            LeaderboardUser(
                id: "6",
                username: "lisa_ma",
                avatarEmoji: "👩‍⚕️",
                score: 1820,
                rank: 6,
                scoreChange: 3,
                badge: nil,
                completedTasks: 82
            ),
            LeaderboardUser(
                id: "7",
                username: "tom_zhao",
                avatarEmoji: "🧑‍🏫",
                score: 1680,
                rank: 7,
                scoreChange: -2,
                badge: nil,
                completedTasks: 75
            ),
            LeaderboardUser(
                id: "8",
                username: "anna_sun",
                avatarEmoji: "👩‍🎤",
                score: 1550,
                rank: 8,
                scoreChange: 1,
                badge: badges[1],
                completedTasks: 68
            ),
            LeaderboardUser(
                id: "9",
                username: "kevin_huang",
                avatarEmoji: "👨‍🌾",
                score: 1420,
                rank: 9,
                scoreChange: 0,
                badge: nil,
                completedTasks: 61
            ),
            LeaderboardUser(
                id: "10",
                username: "jenny_lu",
                avatarEmoji: "👩‍🏭",
                score: 1290,
                rank: 10,
                scoreChange: -1,
                badge: nil,
                completedTasks: 55
            )
        ]
    }

    private func generateMockMyRank(userCount: Int) -> LeaderboardUser {
        LeaderboardUser(
            id: "current_user",
            username: "我",
            avatarEmoji: "🙂",
            score: 890,
            rank: 15,
            scoreChange: 2,
            badge: nil,
            completedTasks: 38
        )
    }
}

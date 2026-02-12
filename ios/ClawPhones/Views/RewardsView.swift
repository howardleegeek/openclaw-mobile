//
//  RewardsView.swift
//  ClawPhones
//
//  Created on 2026-02-11.
//

import SwiftUI

struct RewardsView: View {
    @EnvironmentObject private var auth: AuthViewModel
    @StateObject private var viewModel = RewardsViewModel()

    private static let calendarFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = "M月"
        return formatter
    }()

    var body: some View {
        ScrollView {
            if let rewardsData = viewModel.rewardsData {
                rewardsContent(rewardsData: rewardsData)
            } else if viewModel.isLoading {
                progressView
            } else if viewModel.errorMessage != nil {
                errorView
            }
        }
        .navigationTitle("奖励")
        .task(id: auth.isAuthenticated) {
            if auth.isAuthenticated {
                await loadRewardsData()
            }
        }
        .refreshable {
            if auth.isAuthenticated {
                await loadRewardsData()
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
        .sheet(isPresented: $viewModel.showBadgeSheet) {
            if let badge = viewModel.selectedBadge {
                badgeDetailSheet(badge: badge)
            }
        }
    }

    private func rewardsContent(rewardsData: RewardsData) -> some View {
        VStack(spacing: 24) {
            dailyStreakSection(streakData: rewardsData.streakData)

            Divider()
                .padding(.horizontal)

            earningsSummarySection(summary: rewardsData.earningsSummary)

            Divider()
                .padding(.horizontal)

            availableRewardsSection(rewards: rewardsData.availableRewards)
        }
        .padding()
    }

    // MARK: - Daily Streak Section

    private func dailyStreakSection(streakData: DailyStreakData) -> some View {
        VStack(spacing: 16) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("连续签到")
                        .font(.headline)

                    Text("已连续登录 \(streakData.currentStreak) 天")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }

                Spacer()

                VStack(alignment: .trailing, spacing: 4) {
                    Text("\(streakData.currentStreak)")
                        .font(.system(size: 36, weight: .bold))
                        .foregroundStyle(.orange)

                    Text("/ \(streakData.targetStreak) 天")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            ProgressView(value: Double(streakData.currentStreak), total: Double(streakData.targetStreak))
                .tint(.orange)

            HStack {
                Text("今日奖励")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)

                Spacer()

                if streakData.isLoggedInToday {
                    HStack(spacing: 4) {
                        Image(systemName: "checkmark.circle.fill")
                            .foregroundStyle(.green)
                        Text("已签到 +\(streakData.todayReward)")
                            .font(.subheadline)
                            .foregroundStyle(.green)
                    }
                } else {
                    Button(action: {
                        Task {
                            await viewModel.checkIn()
                        }
                    }) {
                        Text("立即签到 +\(streakData.todayReward)")
                            .font(.subheadline)
                            .foregroundStyle(.white)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 6)
                            .background(
                                Capsule()
                                    .fill(Color.tint)
                            )
                    }
                    .disabled(viewModel.isCheckingIn)
                }
            }

            // Calendar with dots
            streakCalendar(streakData: streakData)
        }
        .padding()
        .background(
            RoundedRectangle(cornerRadius: 20)
                .fill(Color.orange.opacity(0.1))
        )
    }

    private func streakCalendar(streakData: DailyStreakData) -> some View {
        let calendar = Calendar.current
        let now = Date()
        let daysInMonth = calendar.range(of: .day, in: .month, for: now)?.count ?? 30
        let currentDay = calendar.component(.day, from: now)

        return VStack(alignment: .leading, spacing: 12) {
            Text("\(Self.calendarFormatter.string(from: now)) 签到记录")
                .font(.caption)
                .foregroundStyle(.secondary)

            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 7), spacing: 12) {
                ForEach(1...daysInMonth, id: \.self) { day in
                    calendarDay(
                        day: day,
                        currentDay: currentDay,
                        streakData: streakData
                    )
                }
            }

            legend
        }
    }

    private func calendarDay(day: Int, currentDay: Int, streakData: DailyStreakData) -> some View {
        let isCheckedIn = streakData.checkedInDays.contains(day)
        let isToday = day == currentDay
        let isPast = day < currentDay

        return ZStack {
            Circle()
                .fill(
                    isCheckedIn ? Color.orange.opacity(0.3) :
                    isToday ? Color.tint.opacity(0.2) :
                    Color.clear
                )

            VStack(spacing: 2) {
                Text("\(day)")
                    .font(.caption2)
                    .fontWeight(isToday ? .bold : .regular)
                    .foregroundStyle(
                        isCheckedIn ? .orange :
                        isToday ? .tint :
                        isPast ? .secondary : .primary
                    )

                if isCheckedIn {
                    Circle()
                        .fill(.orange)
                        .frame(width: 4, height: 4)
                } else if isPast {
                    Circle()
                        .fill(Color.secondary.opacity(0.3))
                        .frame(width: 4, height: 4)
                }
            }
        }
        .frame(width: 32, height: 32)
    }

    private var legend: some View {
        HStack(spacing: 16) {
            legendItem(color: .orange, text: "已签到")
            legendItem(color: .tint, text: "今天")
            legendItem(color: Color.secondary.opacity(0.3), text: "未签到")
        }
    }

    private func legendItem(color: Color, text: String) -> some View {
        HStack(spacing: 4) {
            Circle()
                .fill(color)
                .frame(width: 8, height: 8)

            Text(text)
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
    }

    // MARK: - Earnings Summary Section

    private func earningsSummarySection(summary: EarningsSummary) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("收益汇总")
                .font(.headline)

            HStack(spacing: 12) {
                summaryCard(
                    title: "今日",
                    value: summary.today,
                    color: .blue,
                    icon: "sun.max.fill"
                )

                summaryCard(
                    title: "本周",
                    value: summary.week,
                    color: .green,
                    icon: "calendar.badge.clock"
                )

                summaryCard(
                    title: "本月",
                    value: summary.month,
                    color: .purple,
                    icon: "calendar"
                )
            }
        }
    }

    private func summaryCard(title: String, value: Int, color: Color, icon: String) -> some View {
        VStack(spacing: 8) {
            Image(systemName: icon)
                .font(.title2)
                .foregroundStyle(color)

            Text("\(value)")
                .font(.system(size: 24, weight: .bold))
                .foregroundStyle(.primary)

            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 16)
        .background(
            RoundedRectangle(cornerRadius: 16)
                .fill(Color(.secondarySystemBackground))
        )
    }

    // MARK: - Available Rewards Section

    private func availableRewardsSection(rewards: [Reward]) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("可用奖励")
                    .font(.headline)

                Spacer()

                Text("\(rewards.count) 个")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            if rewards.isEmpty {
                Text("暂无可用奖励")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .center)
                    .padding(.vertical, 32)
            } else {
                VStack(spacing: 12) {
                    ForEach(rewards) { reward in
                        rewardCard(reward: reward)
                    }
                }
            }
        }
    }

    private func rewardCard(reward: Reward) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                ZStack {
                    Circle()
                        .fill(reward.type.backgroundColor.opacity(0.2))
                        .frame(width: 48, height: 48)

                    Image(systemName: reward.type.icon)
                        .font(.title2)
                        .foregroundStyle(reward.type.color)
                }

                VStack(alignment: .leading, spacing: 4) {
                    Text(reward.title)
                        .font(.headline)

                    Text(reward.description)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }

                Spacer()

                VStack(alignment: .trailing, spacing: 4) {
                    Text("\(reward.credits)")
                        .font(.system(size: 20, weight: .bold))
                        .foregroundStyle(.tint)

                    Text("积分")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }

            // Progress bar for daily limit
            if let dailyLimit = reward.dailyLimit {
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Text("每日限制")
                            .font(.caption2)
                            .foregroundStyle(.secondary)

                        Spacer()

                        Text("\(reward.claimedToday)/\(dailyLimit)")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }

                    ProgressView(value: Double(reward.claimedToday), total: Double(dailyLimit))
                        .tint(.tint)
                }
            }

            HStack(spacing: 12) {
                // Cooldown timer
                if let cooldown = reward.cooldownUntil, cooldown > Date() {
                    HStack(spacing: 4) {
                        Image(systemName: "clock")
                            .font(.caption)

                        Text(cooldownText(for: cooldown))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }

                    Spacer()

                    Text("冷却中")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } else {
                    Spacer()

                    Button(action: {
                        Task {
                            await viewModel.claimReward(rewardId: reward.id)
                        }
                    }) {
                        HStack(spacing: 6) {
                            Image(systemName: "gift.fill")
                            Text("领取")
                        }
                        .font(.subheadline)
                        .fontWeight(.semibold)
                        .foregroundStyle(.white)
                        .padding(.horizontal, 20)
                        .padding(.vertical, 8)
                        .background(
                            Capsule()
                                .fill(reward.canClaim ? Color.tint : Color.gray)
                        )
                    }
                    .disabled(!reward.canClaim || viewModel.isClaiming)
                }
            }
        }
        .padding()
        .background(
            RoundedRectangle(cornerRadius: 16)
                .fill(Color(.secondarySystemBackground))
        )
    }

    private func cooldownText(for date: Date) -> String {
        let components = Calendar.current.dateComponents([.hour, .minute], from: Date(), to: date)

        if let hours = components.hour, hours > 0 {
            return "\(hours)小时后可领取"
        } else if let minutes = components.minute {
            return "\(minutes)分钟后可领取"
        }
        return "即将可领取"
    }

    // MARK: - Badge Detail Sheet

    private func badgeDetailSheet(badge: Badge) -> some View {
        NavigationStack {
            VStack(spacing: 24) {
                VStack(spacing: 16) {
                    ZStack {
                        Circle()
                            .fill(badge.rarity.backgroundColor.opacity(0.2))
                            .frame(width: 100, height: 100)

                        Text(badge.emoji)
                            .font(.system(size: 48))
                    }

                    Text(badge.name)
                        .font(.title2)
                        .fontWeight(.bold)

                    Text(badge.description)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)

                    badgeRequirementCard(badge: badge)
                }

                Spacer()
            }
            .padding()
            .navigationTitle("徽章详情")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") {
                        viewModel.showBadgeSheet = false
                    }
                }
            }
        }
    }

    private func badgeRequirementCard(badge: Badge) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("获得条件")
                .font(.headline)

            ForEach(badge.requirements, id: \.self) { requirement in
                HStack(spacing: 8) {
                    Image(systemName: "checkmark.circle")
                        .foregroundStyle(.green)

                    Text(requirement)
                        .font(.subheadline)
                }
            }
        }
        .padding()
        .background(
            RoundedRectangle(cornerRadius: 16)
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

    private func loadRewardsData() async {
        await viewModel.loadRewardsData()
    }
}

// MARK: - Daily Streak Data

struct DailyStreakData: Codable {
    let currentStreak: Int
    let targetStreak: Int
    let todayReward: Int
    let isLoggedInToday: Bool
    let checkedInDays: [Int]

    var progress: Double {
        return Double(currentStreak) / Double(targetStreak)
    }

    enum CodingKeys: String, CodingKey {
        case currentStreak = "current_streak"
        case targetStreak = "target_streak"
        case todayReward = "today_reward"
        case isLoggedInToday = "is_logged_in_today"
        case checkedInDays = "checked_in_days"
    }
}

// MARK: - Earnings Summary (distinct from ClawTask model)

struct RewardsEarningsSummary: Codable {
    let today: Int
    let week: Int
    let month: Int
    let total: Int
}

// MARK: - Reward Type

enum RewardType: String, Codable {
    case daily = "daily"
    case weekly = "weekly"
    case achievement = "achievement"
    case special = "special"
    case referral = "referral"

    var icon: String {
        switch self {
        case .daily: return "calendar.dayahead.left.fill"
        case .weekly: return "calendar.badge.weekday.fill"
        case .achievement: return "trophy.fill"
        case .special: return "star.fill"
        case .referral: return "person.2.fill"
        }
    }

    var color: Color {
        switch self {
        case .daily: return .blue
        case .weekly: return .green
        case .achievement: return .yellow
        case .special: return .purple
        case .referral: return .pink
        }
    }

    var backgroundColor: Color {
        switch self {
        case .daily: return .blue
        case .weekly: return .green
        case .achievement: return .yellow
        case .special: return .purple
        case .referral: return .pink
        }
    }
}

// MARK: - Reward

struct Reward: Identifiable, Codable {
    let id: String
    let title: String
    let description: String
    let type: RewardType
    let credits: Int
    let dailyLimit: Int?
    let claimedToday: Int
    let cooldownUntil: Date?
    let expiresAt: Date?

    var canClaim: Bool {
        guard let cooldown = cooldownUntil else { return true }
        return cooldown <= Date()
    }

    var hasExpired: Bool {
        guard let expires = expiresAt else { return false }
        return expires < Date()
    }

    var isDailyLimitReached: Bool {
        guard let limit = dailyLimit else { return false }
        return claimedToday >= limit
    }

    enum CodingKeys: String, CodingKey {
        case id
        case title
        case description
        case type
        case credits
        case dailyLimit = "daily_limit"
        case claimedToday = "claimed_today"
        case cooldownUntil = "cooldown_until"
        case expiresAt = "expires_at"
    }
}

// MARK: - Badge Rarity

enum BadgeRarity: String, Codable {
    case common = "common"
    case rare = "rare"
    case epic = "epic"
    case legendary = "legendary"

    var displayName: String {
        switch self {
        case .common: return "普通"
        case .rare: return "稀有"
        case .epic: return "史诗"
        case .legendary: return "传说"
        }
    }

    var color: Color {
        switch self {
        case .common: return .gray
        case .rare: return .blue
        case .epic: return .purple
        case .legendary: return .yellow
        }
    }

    var backgroundColor: Color {
        switch self {
        case .common: return .gray
        case .rare: return .blue
        case .epic: return .purple
        case .legendary: return .yellow
        }
    }
}

// MARK: - Badge

struct RewardBadge: Identifiable, Codable {
    let id: String
    let name: String
    let description: String
    let emoji: String
    let rarity: BadgeRarity
    let requirements: [String]
    let unlockedAt: Date?

    var isUnlocked: Bool {
        unlockedAt != nil
    }

    enum CodingKeys: String, CodingKey {
        case id
        case name
        case description
        case emoji
        case rarity
        case requirements
        case unlockedAt = "unlocked_at"
    }
}

// MARK: - Rewards Data

struct RewardsData: Codable {
    let streakData: DailyStreakData
    let earningsSummary: RewardsEarningsSummary
    let availableRewards: [Reward]
    let badges: [RewardBadge]

    enum CodingKeys: String, CodingKey {
        case streakData = "streak_data"
        case earningsSummary = "earnings_summary"
        case availableRewards = "available_rewards"
        case badges
    }
}

// MARK: - Rewards View Model

@MainActor
final class RewardsViewModel: ObservableObject {
    @Published var rewardsData: RewardsData?
    @Published var isLoading: Bool = false
    @Published var errorMessage: String?
    @Published var showBadgeSheet: Bool = false
    @Published var selectedBadge: Badge?
    @Published var isCheckingIn: Bool = false
    @Published var isClaiming: Bool = false

    func loadRewardsData() async {
        isLoading = true
        defer { isLoading = false }

        do {
            try await Task.sleep(nanoseconds: 500_000_000)

            let calendar = Calendar.current
            let currentDay = calendar.component(.day, from: Date())

            rewardsData = RewardsData(
                streakData: DailyStreakData(
                    currentStreak: 7,
                    targetStreak: 30,
                    todayReward: 10,
                    isLoggedInToday: false,
                    checkedInDays: Array(1...currentDay - 1).filter { _ in Bool.random() }
                ),
                earningsSummary: RewardsEarningsSummary(
                    today: 85,
                    week: 520,
                    month: 1850,
                    total: 12450
                ),
                availableRewards: generateMockRewards(),
                badges: generateMockBadges()
            )
        } catch {
            errorMessage = "加载奖励数据失败"
        }
    }

    func checkIn() async {
        isCheckingIn = true
        defer { isCheckingIn = false }

        do {
            try await Task.sleep(nanoseconds: 1_000_000_000)

            if var rewardsData = rewardsData {
                let calendar = Calendar.current
                let currentDay = calendar.component(.day, from: Date())

                var streakData = rewardsData.streakData
                streakData = DailyStreakData(
                    currentStreak: streakData.currentStreak + 1,
                    targetStreak: streakData.targetStreak,
                    todayReward: streakData.todayReward + 5,
                    isLoggedInToday: true,
                    checkedInDays: streakData.checkedInDays + [currentDay]
                )

                self.rewardsData = RewardsData(
                    streakData: streakData,
                    earningsSummary: RewardsEarningsSummary(
                        today: rewardsData.earningsSummary.today + streakData.todayReward,
                        week: rewardsData.earningsSummary.week,
                        month: rewardsData.earningsSummary.month,
                        total: rewardsData.earningsSummary.total
                    ),
                    availableRewards: rewardsData.availableRewards,
                    badges: rewardsData.badges
                )
            }
        } catch {
            errorMessage = "签到失败，请重试"
        }
    }

    func claimReward(rewardId: String) async {
        isClaiming = true
        defer { isClaiming = false }

        do {
            try await Task.sleep(nanoseconds: 1_000_000_000)

            if var rewardsData = rewardsData,
               let index = rewardsData.availableRewards.firstIndex(where: { $0.id == rewardId }) {
                var rewards = rewardsData.availableRewards
                rewards[index] = Reward(
                    id: rewards[index].id,
                    title: rewards[index].title,
                    description: rewards[index].description,
                    type: rewards[index].type,
                    credits: rewards[index].credits,
                    dailyLimit: rewards[index].dailyLimit,
                    claimedToday: (rewards[index].claimedToday + 1),
                    cooldownUntil: Date().addingTimeInterval(3600),
                    expiresAt: rewards[index].expiresAt
                )

                self.rewardsData = RewardsData(
                    streakData: rewardsData.streakData,
                    earningsSummary: rewardsData.earningsSummary,
                    availableRewards: rewards,
                    badges: rewardsData.badges
                )
            }
        } catch {
            errorMessage = "领取奖励失败"
        }
    }

    func showBadgeDetail(badge: Badge) {
        selectedBadge = badge
        showBadgeSheet = true
    }

    private func generateMockRewards() -> [Reward] {
        [
            Reward(
                id: "daily_bonus",
                title: "每日登录奖励",
                description: "每天登录即可领取的积分奖励",
                type: .daily,
                credits: 20,
                dailyLimit: 1,
                claimedToday: 0,
                cooldownUntil: nil,
                expiresAt: nil
            ),
            Reward(
                id: "weekly_bonus",
                title: "周任务奖励",
                description: "完成本周任务可领取额外积分",
                type: .weekly,
                credits: 100,
                dailyLimit: nil,
                claimedToday: 0,
                cooldownUntil: Date().addingTimeInterval(7200),
                expiresAt: nil
            ),
            Reward(
                id: "achievement_bonus",
                title: "首次完成奖励",
                description: "首次完成任意类型任务",
                type: .achievement,
                credits: 50,
                dailyLimit: nil,
                claimedToday: 0,
                cooldownUntil: nil,
                expiresAt: Date().addingTimeInterval(86400 * 7)
            ),
            Reward(
                id: "referral_bonus",
                title: "邀请好友奖励",
                description: "邀请新用户注册可获得积分",
                type: .referral,
                credits: 200,
                dailyLimit: 5,
                claimedToday: 2,
                cooldownUntil: nil,
                expiresAt: nil
            ),
            Reward(
                id: "special_event",
                title: "限时活动奖励",
                description: "参与特殊活动可获得双倍积分",
                type: .special,
                credits: 150,
                dailyLimit: 3,
                claimedToday: 0,
                cooldownUntil: nil,
                expiresAt: Date().addingTimeInterval(86400 * 2)
            )
        ]
    }

    private func generateMockBadges() -> [RewardBadge] {
        [
            RewardBadge(
                id: "first_task",
                name: "初出茅庐",
                description: "完成第一个任务",
                emoji: "🌱",
                rarity: .common,
                requirements: ["完成1个任务"],
                unlockedAt: Date().addingTimeInterval(-86400 * 5)
            ),
            RewardBadge(
                id: "week_streak",
                name: "七日连签",
                description: "连续签到7天",
                emoji: "🔥",
                rarity: .rare,
                requirements: ["连续签到7天"],
                unlockedAt: Date().addingTimeInterval(-86400 * 2)
            ),
            RewardBadge(
                id: "task_master",
                name: "任务大师",
                description: "累计完成100个任务",
                emoji: "🏆",
                rarity: .epic,
                requirements: ["完成100个任务"],
                unlockedAt: nil
            ),
            RewardBadge(
                id: "top_contributor",
                name: "顶尖贡献者",
                description: "累计获得10000积分",
                emoji: "👑",
                rarity: .legendary,
                requirements: ["获得10000积分", "连续签到30天"],
                unlockedAt: nil
            )
        ]
    }
}

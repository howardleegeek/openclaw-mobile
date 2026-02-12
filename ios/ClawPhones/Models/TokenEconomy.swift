//
//  TokenEconomy.swift
//  ClawPhones
//
//  Created on 2026-02-11.
//

import Foundation

// MARK: - Wallet Balance
struct WalletBalance: Codable {
    let totalCredits: Int
    let availableCredits: Int
    let pendingCredits: Int
    let lifetimeEarned: Int
    let lifetimeSpent: Int

    enum CodingKeys: String, CodingKey {
        case totalCredits = "total_credits"
        case availableCredits = "available_credits"
        case pendingCredits = "pending_credits"
        case lifetimeEarned = "lifetime_earned"
        case lifetimeSpent = "lifetime_spent"
    }

    var pendingTotal: Int {
        return pendingCredits
    }

    var spendableCredits: Int {
        return availableCredits
    }

    init(totalCredits: Int = 0, availableCredits: Int = 0, pendingCredits: Int = 0, lifetimeEarned: Int = 0, lifetimeSpent: Int = 0) {
        self.totalCredits = totalCredits
        self.availableCredits = availableCredits
        self.pendingCredits = pendingCredits
        self.lifetimeEarned = lifetimeEarned
        self.lifetimeSpent = lifetimeSpent
    }
}

// MARK: - Transaction Type
enum TransactionType: String, Codable, CaseIterable {
    case credit = "credit"
    case debit = "debit"
    case transfer = "transfer"
    case reward = "reward"
    case penalty = "penalty"

    var displayName: String {
        switch self {
        case .credit: return "Credit"
        case .debit: return "Debit"
        case .transfer: return "Transfer"
        case .reward: return "Reward"
        case .penalty: return "Penalty"
        }
    }

    var iconName: String {
        switch self {
        case .credit: return "plus.circle.fill"
        case .debit: return "minus.circle.fill"
        case .transfer: return "arrow.left.arrow.right.circle.fill"
        case .reward: return "gift.fill"
        case .penalty: return "exclamationmark.triangle.fill"
        }
    }

    var color: String {
        switch self {
        case .credit, .reward: return "green"
        case .debit, .penalty: return "red"
        case .transfer: return "blue"
        }
    }
}

// MARK: - Transaction Status
enum TransactionStatus: String, Codable, CaseIterable {
    case pending = "pending"
    case completed = "completed"
    case failed = "failed"

    var displayName: String {
        switch self {
        case .pending: return "Pending"
        case .completed: return "Completed"
        case .failed: return "Failed"
        }
    }

    var isActive: Bool {
        return self == .pending
    }
}

// MARK: - Transaction
struct Transaction: Codable, Identifiable {
    let id: String
    let type: TransactionType
    let amount: Int
    let description: String
    let counterpartyId: String?
    let taskId: String?
    let createdAt: Date
    let status: TransactionStatus

    enum CodingKeys: String, CodingKey {
        case id
        case type
        case amount
        case description
        case counterpartyId = "counterparty_id"
        case taskId = "task_id"
        case createdAt = "created_at"
        case status
    }

    var isCredit: Bool {
        type == .credit || type == .reward
    }

    var isDebit: Bool {
        type == .debit || type == .penalty
    }

    var isTransfer: Bool {
        type == .transfer
    }

    var formattedAmount: String {
        let prefix = isCredit || type == .reward ? "+" : "-"
        return "\(prefix)\(amount) Credits"
    }

    var displayDate: String {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter.string(from: createdAt)
    }

    // WalletView compatibility
    var title: String {
        return description
    }

    var note: String? {
        return nil // Note is part of description
    }

    var date: Date {
        return createdAt
    }

    var amountText: String {
        return formattedAmount
    }
}

// MARK: - TransactionType Extensions for WalletView compatibility
import SwiftUI

extension TransactionType {
    var icon: String {
        return iconName
    }

    var backgroundColor: Color {
        switch self {
        case .credit, .reward:
            return Color.green
        case .debit, .penalty:
            return Color.red
        case .transfer:
            return Color.blue
        }
    }
}

// MARK: - Reward Trigger Type
enum RewardTriggerType: String, Codable, CaseIterable {
    case dailyLogin = "daily_login"
    case taskComplete = "task_complete"
    case communityAlert = "community_alert"
    case edgeJob = "edge_job"
    case referral = "referral"
    case streak = "streak"

    var displayName: String {
        switch self {
        case .dailyLogin: return "Daily Login"
        case .taskComplete: return "Task Completion"
        case .communityAlert: return "Community Alert"
        case .edgeJob: return "Edge Job"
        case .referral: return "Referral"
        case .streak: return "Streak Bonus"
        }
    }

    var iconName: String {
        switch self {
        case .dailyLogin: return "calendar"
        case .taskComplete: return "checkmark.circle"
        case .communityAlert: return "bell"
        case .edgeJob: return "cpu"
        case .referral: return "person.2"
        case .streak: return "flame"
        }
    }
}

// MARK: - Reward Rule
struct RewardRule: Codable, Identifiable {
    let id: String
    let name: String
    let description: String
    let triggerType: RewardTriggerType
    let rewardCredits: Int
    let cooldownMinutes: Int
    let maxPerDay: Int

    enum CodingKeys: String, CodingKey {
        case id
        case name
        case description
        case triggerType = "trigger_type"
        case rewardCredits = "reward_credits"
        case cooldownMinutes = "cooldown_minutes"
        case maxPerDay = "max_per_day"
    }

    var canClaim: Bool {
        // This would typically be checked against user's claim history
        return true
    }

    var cooldownFormatted: String {
        if cooldownMinutes < 60 {
            return "\(cooldownMinutes) min"
        } else if cooldownMinutes < 1440 {
            let hours = cooldownMinutes / 60
            return "\(hours) hr"
        } else {
            let days = cooldownMinutes / 1440
            return "\(days) day\(days > 1 ? "s" : "")"
        }
    }
}

// MARK: - Badge
enum Badge: String, Codable, CaseIterable {
    case newcomer = "newcomer"
    case contributor = "contributor"
    case guardian = "guardian"
    case powerNode = "power_node"
    case communityLeader = "community_leader"
    case topEarner = "top_earner"

    var displayName: String {
        switch self {
        case .newcomer: return "Newcomer"
        case .contributor: return "Contributor"
        case .guardian: return "Guardian"
        case .powerNode: return "Power Node"
        case .communityLeader: return "Community Leader"
        case .topEarner: return "Top Earner"
        }
    }

    var iconName: String {
        switch self {
        case .newcomer: return "star.fill"
        case .contributor: return "heart.fill"
        case .guardian: return "shield.fill"
        case .powerNode: return "bolt.fill"
        case .communityLeader: return "crown.fill"
        case .topEarner: return "trophy.fill"
        }
    }

    var color: String {
        switch self {
        case .newcomer: return "blue"
        case .contributor: return "green"
        case .guardian: return "orange"
        case .powerNode: return "purple"
        case .communityLeader: return "red"
        case .topEarner: return "yellow"
        }
    }
}

// MARK: - Leaderboard Entry
struct LeaderboardEntry: Codable, Identifiable {
    var id: String { userId }
    let rank: Int
    let userId: String
    let displayName: String
    let credits: Int
    let badge: Badge?

    enum CodingKeys: String, CodingKey {
        case rank
        case userId = "user_id"
        case displayName = "display_name"
        case credits
        case badge
    }

    var rankDisplay: String {
        switch rank {
        case 1: return "🥇"
        case 2: return "🥈"
        case 3: return "🥉"
        default: return "\(rank)"
        }
    }

    var formattedCredits: String {
        let formatter = NumberFormatter()
        formatter.numberStyle = .decimal
        return formatter.string(from: NSNumber(value: credits)) ?? "\(credits)"
    }
}

// MARK: - Leaderboard Period
enum LeaderboardPeriod: String, Codable, CaseIterable {
    case daily = "daily"
    case weekly = "weekly"
    case monthly = "monthly"
    case allTime = "all_time"

    var displayName: String {
        switch self {
        case .daily: return "Today"
        case .weekly: return "This Week"
        case .monthly: return "This Month"
        case .allTime: return "All Time"
        }
    }
}

// MARK: - Leaderboard
struct Leaderboard: Codable {
    let entries: [LeaderboardEntry]
    let myRank: Int?
    let period: LeaderboardPeriod

    enum CodingKeys: String, CodingKey {
        case entries
        case myRank = "my_rank"
        case period
    }

    var topThree: [LeaderboardEntry] {
        return Array(entries.prefix(3))
    }

    var topUsers: [LeaderboardEntry] {
        return Array(entries.prefix(3))
    }

    var users: [LeaderboardEntry] {
        return Array(entries.dropFirst(3))
    }

    var isRanked: Bool {
        return myRank != nil
    }

    var myRankDisplay: String {
        guard let rank = myRank else {
            return "Not Ranked"
        }
        if rank <= 3 {
            return [1: "🥇", 2: "🥈", 3: "🥉"][rank] ?? "\(rank)"
        }
        return "\(rank)"
    }

    var totalParticipants: Int {
        return entries.count
    }
}

// MARK: - Transfer Request
struct TransferRequest: Codable {
    let recipientId: String
    let amount: Int
    let message: String?

    enum CodingKeys: String, CodingKey {
        case recipientId = "recipient_id"
        case amount
        case message
    }

    var isValid: Bool {
        return amount > 0
    }
}

// MARK: - Transfer Response
struct TransferResponse: Codable {
    let success: Bool
    let transactionId: String?
    let newBalance: WalletBalance?
    let error: String?

    enum CodingKeys: String, CodingKey {
        case success
        case transactionId = "transaction_id"
        case newBalance = "new_balance"
        case error
    }
}

// MARK: - Claim Reward Request
struct ClaimRewardRequest: Codable {
    let rewardRuleId: String

    enum CodingKeys: String, CodingKey {
        case rewardRuleId = "reward_rule_id"
    }
}

// MARK: - Claim Reward Response
struct ClaimRewardResponse: Codable {
    let success: Bool
    let rewardCredits: Int?
    let newBalance: WalletBalance?
    let message: String?
    let error: String?

    enum CodingKeys: String, CodingKey {
        case success
        case rewardCredits = "reward_credits"
        case newBalance = "new_balance"
        case message
        case error
    }
}

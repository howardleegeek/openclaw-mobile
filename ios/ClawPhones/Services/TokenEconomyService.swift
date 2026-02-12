//
//  TokenEconomyService.swift
//  ClawPhones
//
//  Created on 2026-02-11.
//

import Foundation
import Combine

// MARK: - Service Error
enum TokenEconomyError: Error, LocalizedError {
    case networkError(Error)
    case invalidResponse
    case insufficientCredits
    case invalidRecipient
    case transferFailed
    case rewardNotAvailable
    case rewardClaimedRecently
    case maxRewardsReached
    case unauthorized
    case serverError(Int)
    case decodingError

    var errorDescription: String? {
        switch self {
        case .networkError(let error):
            return "Network error: \(error.localizedDescription)"
        case .invalidResponse:
            return "Invalid response from server"
        case .insufficientCredits:
            return "Insufficient credits for this transaction"
        case .invalidRecipient:
            return "Invalid recipient for transfer"
        case .transferFailed:
            return "Transfer failed"
        case .rewardNotAvailable:
            return "Reward is not currently available"
        case .rewardClaimedRecently:
            return "You have already claimed this reward recently"
        case .maxRewardsReached:
            return "You have reached the maximum claims for this reward today"
        case .unauthorized:
            return "Unauthorized access"
        case .serverError(let code):
            return "Server error: \(code)"
        case .decodingError:
            return "Failed to decode response"
        }
    }
}

// MARK: - API Response Wrapper
private struct TokenEconomyAPIResponse<T: Decodable>: Decodable {
    let success: Bool
    let data: T?
    let message: String?
}

// MARK: - Token Economy Service
class TokenEconomyService: ObservableObject {
    // MARK: - Published Properties
    @Published var wallet: WalletBalance = WalletBalance()
    @Published var transactions: [Transaction] = []
    @Published var rewardRules: [RewardRule] = []
    @Published var leaderboard: Leaderboard?
    @Published var isLoading: Bool = false
    @Published var errorMessage: String?

    // MARK: - Constants
    private let baseURL: String
    private let defaultPageSize: Int = 50

    // MARK: - Private Properties
    private var cancellables = Set<AnyCancellable>()

    // MARK: - Singleton
    static let shared = TokenEconomyService()

    // MARK: - Initialization
    private init(baseURL: String = "https://api.clawphones.com") {
        self.baseURL = baseURL
    }

    // MARK: - Public Methods

    /// Fetch the current wallet balance for the user
    /// - Returns: Wallet balance
    func fetchWallet() async throws -> WalletBalance {
        isLoading = true
        defer { isLoading = false }

        guard let url = URL(string: "\(baseURL)/v1/tokens/wallet") else {
            throw TokenEconomyError.invalidResponse
        }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        if let token = AuthManager.shared.accessToken {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        do {
            let (data, response) = try await URLSession.shared.data(for: request)

            guard let httpResponse = response as? HTTPURLResponse else {
                throw TokenEconomyError.invalidResponse
            }

            guard (200...299).contains(httpResponse.statusCode) else {
                if httpResponse.statusCode == 401 {
                    throw TokenEconomyError.unauthorized
                }
                throw TokenEconomyError.serverError(httpResponse.statusCode)
            }

            let apiResponse = try JSONDecoder().decode(TokenEconomyAPIResponse<WalletBalance>.self, from: data)

            guard let balance = apiResponse.data else {
                throw TokenEconomyError.decodingError
            }

            await MainActor.run {
                self.wallet = balance
                self.errorMessage = nil
            }

            return balance
        } catch let error as TokenEconomyError {
            await MainActor.run {
                self.errorMessage = error.errorDescription
            }
            throw error
        } catch {
            await MainActor.run {
                self.errorMessage = error.localizedDescription
            }
            throw TokenEconomyError.networkError(error)
        }
    }

    /// Fetch transactions for the user
    /// - Parameters:
    ///   - page: Page number (default: 1)
    ///   - limit: Items per page (default: 50)
    ///   - type: Optional filter by transaction type
    ///   - status: Optional filter by transaction status
    /// - Returns: Array of transactions
    func fetchTransactions(
        page: Int = 1,
        limit: Int = 50,
        type: TransactionType? = nil,
        status: TransactionStatus? = nil
    ) async throws -> [Transaction] {
        isLoading = true
        defer { isLoading = false }

        var urlComponents = URLComponents(string: "\(baseURL)/v1/tokens/transactions")
        var queryItems: [URLQueryItem] = [
            URLQueryItem(name: "page", value: String(page)),
            URLQueryItem(name: "limit", value: String(limit))
        ]

        if let type = type {
            queryItems.append(URLQueryItem(name: "type", value: type.rawValue))
        }

        if let status = status {
            queryItems.append(URLQueryItem(name: "status", value: status.rawValue))
        }

        urlComponents?.queryItems = queryItems

        guard let url = urlComponents?.url else {
            throw TokenEconomyError.invalidResponse
        }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        if let token = AuthManager.shared.accessToken {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        do {
            let (data, response) = try await URLSession.shared.data(for: request)

            guard let httpResponse = response as? HTTPURLResponse else {
                throw TokenEconomyError.invalidResponse
            }

            guard (200...299).contains(httpResponse.statusCode) else {
                if httpResponse.statusCode == 401 {
                    throw TokenEconomyError.unauthorized
                }
                throw TokenEconomyError.serverError(httpResponse.statusCode)
            }

            let apiResponse = try JSONDecoder().decode(TokenEconomyAPIResponse<[Transaction]>.self, from: data)

            guard let transactions = apiResponse.data else {
                throw TokenEconomyError.decodingError
            }

            await MainActor.run {
                if page == 1 {
                    self.transactions = transactions
                } else {
                    self.transactions.append(contentsOf: transactions)
                }
                self.errorMessage = nil
            }

            return transactions
        } catch let error as TokenEconomyError {
            await MainActor.run {
                self.errorMessage = error.errorDescription
            }
            throw error
        } catch {
            await MainActor.run {
                self.errorMessage = error.localizedDescription
            }
            throw TokenEconomyError.networkError(error)
        }
    }

    /// Transfer credits to another user
    /// - Parameters:
    ///   - recipientId: The ID of the recipient user
    ///   - amount: Amount of credits to transfer
    ///   - message: Optional message to include with the transfer
    /// - Returns: Transaction ID and new balance
    func transferCredits(
        recipientId: String,
        amount: Int,
        message: String? = nil
    ) async throws -> (transactionId: String, newBalance: WalletBalance) {
        isLoading = true
        defer { isLoading = false }

        // Check sufficient balance
        guard amount <= wallet.availableCredits else {
            throw TokenEconomyError.insufficientCredits
        }

        guard amount > 0 else {
            throw TokenEconomyError.invalidResponse
        }

        guard let url = URL(string: "\(baseURL)/v1/tokens/transfer") else {
            throw TokenEconomyError.invalidResponse
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        if let token = AuthManager.shared.accessToken {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        let transferRequest = TransferRequest(
            recipientId: recipientId,
            amount: amount,
            message: message
        )

        request.httpBody = try? JSONEncoder().encode(transferRequest)

        do {
            let (data, response) = try await URLSession.shared.data(for: request)

            guard let httpResponse = response as? HTTPURLResponse else {
                throw TokenEconomyError.invalidResponse
            }

            switch httpResponse.statusCode {
            case 200...299:
                let apiResponse = try JSONDecoder().decode(TokenEconomyAPIResponse<TransferResponse>.self, from: data)

                guard let transferResponse = apiResponse.data else {
                    throw TokenEconomyError.decodingError
                }

                guard transferResponse.success,
                      let transactionId = transferResponse.transactionId,
                      let newBalance = transferResponse.newBalance else {
                    throw TokenEconomyError.transferFailed
                }

                await MainActor.run {
                    self.wallet = newBalance
                    self.errorMessage = nil
                }

                return (transactionId, newBalance)

            case 400:
                throw TokenEconomyError.insufficientCredits
            case 404:
                throw TokenEconomyError.invalidRecipient
            case 401:
                throw TokenEconomyError.unauthorized
            default:
                throw TokenEconomyError.serverError(httpResponse.statusCode)
            }
        } catch let error as TokenEconomyError {
            await MainActor.run {
                self.errorMessage = error.errorDescription
            }
            throw error
        } catch {
            await MainActor.run {
                self.errorMessage = error.localizedDescription
            }
            throw TokenEconomyError.networkError(error)
        }
    }

    /// Claim a reward by reward rule ID
    /// - Parameter rewardRuleId: The ID of the reward rule to claim
    /// - Returns: Reward credits claimed and new balance
    func claimReward(rewardRuleId: String) async throws -> (credits: Int, newBalance: WalletBalance) {
        isLoading = true
        defer { isLoading = false }

        guard let url = URL(string: "\(baseURL)/v1/tokens/rewards/claim") else {
            throw TokenEconomyError.invalidResponse
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        if let token = AuthManager.shared.accessToken {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        let claimRequest = ClaimRewardRequest(rewardRuleId: rewardRuleId)

        request.httpBody = try? JSONEncoder().encode(claimRequest)

        do {
            let (data, response) = try await URLSession.shared.data(for: request)

            guard let httpResponse = response as? HTTPURLResponse else {
                throw TokenEconomyError.invalidResponse
            }

            switch httpResponse.statusCode {
            case 200...299:
                let apiResponse = try JSONDecoder().decode(TokenEconomyAPIResponse<ClaimRewardResponse>.self, from: data)

                guard let claimResponse = apiResponse.data else {
                    throw TokenEconomyError.decodingError
                }

                guard claimResponse.success,
                      let rewardCredits = claimResponse.rewardCredits,
                      let newBalance = claimResponse.newBalance else {
                    if let error = claimResponse.error {
                        // Parse error message for specific error cases
                        if error.contains("cooldown") || error.contains("recently") {
                            throw TokenEconomyError.rewardClaimedRecently
                        } else if error.contains("max") || error.contains("limit") {
                            throw TokenEconomyError.maxRewardsReached
                        } else if error.contains("available") {
                            throw TokenEconomyError.rewardNotAvailable
                        }
                    }
                    throw TokenEconomyError.transferFailed
                }

                await MainActor.run {
                    self.wallet = newBalance
                    self.errorMessage = nil
                }

                return (rewardCredits, newBalance)

            case 400:
                throw TokenEconomyError.rewardNotAvailable
            case 409:
                throw TokenEconomyError.rewardClaimedRecently
            case 429:
                throw TokenEconomyError.maxRewardsReached
            case 401:
                throw TokenEconomyError.unauthorized
            default:
                throw TokenEconomyError.serverError(httpResponse.statusCode)
            }
        } catch let error as TokenEconomyError {
            await MainActor.run {
                self.errorMessage = error.errorDescription
            }
            throw error
        } catch {
            await MainActor.run {
                self.errorMessage = error.localizedDescription
            }
            throw TokenEconomyError.networkError(error)
        }
    }

    /// Fetch leaderboard for a specific period
    /// - Parameter period: The leaderboard period (default: weekly)
    /// - Returns: Leaderboard data
    func fetchLeaderboard(period: LeaderboardPeriod = .weekly) async throws -> Leaderboard {
        isLoading = true
        defer { isLoading = false }

        var urlComponents = URLComponents(string: "\(baseURL)/v1/tokens/leaderboard")
        urlComponents?.queryItems = [
            URLQueryItem(name: "period", value: period.rawValue)
        ]

        guard let url = urlComponents?.url else {
            throw TokenEconomyError.invalidResponse
        }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        if let token = AuthManager.shared.accessToken {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        do {
            let (data, response) = try await URLSession.shared.data(for: request)

            guard let httpResponse = response as? HTTPURLResponse else {
                throw TokenEconomyError.invalidResponse
            }

            guard (200...299).contains(httpResponse.statusCode) else {
                if httpResponse.statusCode == 401 {
                    throw TokenEconomyError.unauthorized
                }
                throw TokenEconomyError.serverError(httpResponse.statusCode)
            }

            let apiResponse = try JSONDecoder().decode(TokenEconomyAPIResponse<Leaderboard>.self, from: data)

            guard let leaderboard = apiResponse.data else {
                throw TokenEconomyError.decodingError
            }

            await MainActor.run {
                self.leaderboard = leaderboard
                self.errorMessage = nil
            }

            return leaderboard
        } catch let error as TokenEconomyError {
            await MainActor.run {
                self.errorMessage = error.errorDescription
            }
            throw error
        } catch {
            await MainActor.run {
                self.errorMessage = error.localizedDescription
            }
            throw TokenEconomyError.networkError(error)
        }
    }

    /// Fetch all available reward rules
    /// - Returns: Array of reward rules
    func fetchRewardRules() async throws -> [RewardRule] {
        isLoading = true
        defer { isLoading = false }

        guard let url = URL(string: "\(baseURL)/v1/tokens/rewards/rules") else {
            throw TokenEconomyError.invalidResponse
        }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        if let token = AuthManager.shared.accessToken {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        do {
            let (data, response) = try await URLSession.shared.data(for: request)

            guard let httpResponse = response as? HTTPURLResponse else {
                throw TokenEconomyError.invalidResponse
            }

            guard (200...299).contains(httpResponse.statusCode) else {
                if httpResponse.statusCode == 401 {
                    throw TokenEconomyError.unauthorized
                }
                throw TokenEconomyError.serverError(httpResponse.statusCode)
            }

            let apiResponse = try JSONDecoder().decode(TokenEconomyAPIResponse<[RewardRule]>.self, from: data)

            guard let rules = apiResponse.data else {
                throw TokenEconomyError.decodingError
            }

            await MainActor.run {
                self.rewardRules = rules
                self.errorMessage = nil
            }

            return rules
        } catch let error as TokenEconomyError {
            await MainActor.run {
                self.errorMessage = error.errorDescription
            }
            throw error
        } catch {
            await MainActor.run {
                self.errorMessage = error.localizedDescription
            }
            throw TokenEconomyError.networkError(error)
        }
    }

    /// Check and claim the daily login reward
    /// - Returns: Reward credits claimed (or 0 if not available)
    func checkAndClaimDailyLogin() async throws -> Int {
        // Find the daily login reward rule
        let dailyLoginRule = rewardRules.first { $0.triggerType == .dailyLogin }

        guard let rule = dailyLoginRule else {
            // Fetch reward rules if not already loaded
            _ = try await fetchRewardRules()

            if let fetchedRule = rewardRules.first(where: { $0.triggerType == .dailyLogin }) {
                return try await claimReward(rewardRuleId: fetchedRule.id).credits
            }

            return 0
        }

        do {
            let (credits, _) = try await claimReward(rewardRuleId: rule.id)
            return credits
        } catch TokenEconomyError.rewardClaimedRecently {
            // User has already claimed today
            return 0
        } catch TokenEconomyError.maxRewardsReached {
            // Max rewards reached for today
            return 0
        } catch {
            throw error
        }
    }

    // MARK: - Helper Methods

    /// Get transactions filtered by type
    /// - Parameter type: Transaction type to filter by
    /// - Returns: Filtered transactions
    func getTransactions(byType type: TransactionType) -> [Transaction] {
        return transactions.filter { $0.type == type }
    }

    /// Get transactions filtered by status
    /// - Parameter status: Transaction status to filter by
    /// - Returns: Filtered transactions
    func getTransactions(byStatus status: TransactionStatus) -> [Transaction] {
        return transactions.filter { $0.status == status }
    }

    /// Get pending transactions
    /// - Returns: Array of pending transactions
    func getPendingTransactions() -> [Transaction] {
        return getTransactions(byStatus: .pending)
    }

    /// Get completed transactions
    /// - Returns: Array of completed transactions
    func getCompletedTransactions() -> [Transaction] {
        return getTransactions(byStatus: .completed)
    }

    /// Get credit transactions (credits and rewards)
    /// - Returns: Array of credit transactions
    func getCreditTransactions() -> [Transaction] {
        return transactions.filter { $0.isCredit }
    }

    /// Get debit transactions (debits and penalties)
    /// - Returns: Array of debit transactions
    func getDebitTransactions() -> [Transaction] {
        return transactions.filter { $0.isDebit }
    }

    /// Get available rewards (rewards that can be claimed)
    /// - Returns: Array of available reward rules
    func getAvailableRewards() -> [RewardRule] {
        return rewardRules.filter { $0.canClaim }
    }

    /// Calculate total credits earned from a specific transaction type
    /// - Parameter type: Transaction type
    /// - Returns: Total amount for that type
    func getTotalAmount(forType type: TransactionType) -> Int {
        return transactions
            .filter { $0.type == type && $0.status == .completed }
            .reduce(0) { $0 + $1.amount }
    }

    /// Check if user can transfer specified amount
    /// - Parameter amount: Amount to transfer
    /// - Returns: Boolean indicating if transfer is possible
    func canTransfer(amount: Int) -> Bool {
        return amount > 0 && amount <= wallet.availableCredits
    }

    /// Format credits for display
    /// - Parameter credits: Number of credits
    /// - Returns: Formatted string
    func formatCredits(_ credits: Int) -> String {
        let formatter = NumberFormatter()
        formatter.numberStyle = .decimal
        return formatter.string(from: NSNumber(value: credits)) ?? "\(credits)"
    }
}

// MARK: - Auth Manager (Reference)
// AuthManager is defined in Services/TaskMarketService.swift

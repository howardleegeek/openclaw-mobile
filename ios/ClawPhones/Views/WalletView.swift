//
//  WalletView.swift
//  ClawPhones
//
//  Created on 2026-02-11.
//

import SwiftUI

struct WalletView: View {
    @EnvironmentObject private var auth: AuthViewModel
    @StateObject private var viewModel = WalletViewModel()

    private static let dateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = "M月d日 HH:mm"
        return formatter
    }()

    var body: some View {
        ZStack(alignment: .bottom) {
            ScrollView {
                if let walletData = viewModel.walletData {
                    walletContent(walletData: walletData)
                } else if viewModel.isLoading {
                    progressView
                } else if viewModel.errorMessage != nil {
                    errorView
                }
            }
            .navigationTitle("钱包")
            .task(id: auth.isAuthenticated) {
                if auth.isAuthenticated {
                    await loadWalletData()
                }
            }
            .refreshable {
                if auth.isAuthenticated {
                    await loadWalletData()
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

            sendButton
        }
    }

    private func walletContent(walletData: WalletData) -> some View {
        VStack(spacing: 24) {
            balanceCardSection(walletData: walletData)

            Divider()
                .padding(.horizontal)

            quickActionsSection

            Divider()
                .padding(.horizontal)

            transactionsFilterSection

            transactionsSection(transactions: filteredTransactions)
        }
        .padding()
    }

    private func balanceCardSection(walletData: WalletData) -> some View {
        VStack(spacing: 16) {
            Text("可用余额")
                .font(.subheadline)
                .foregroundStyle(.secondary)

            Text("\(walletData.availableCredits)")
                .font(.system(size: 56, weight: .bold))
                .foregroundStyle(.tint)

            HStack(spacing: 24) {
                balanceBreakdown(
                    title: "可用",
                    value: walletData.availableCredits,
                    color: .green,
                    icon: "checkmark.circle"
                )

                balanceBreakdown(
                    title: "待入账",
                    value: walletData.pendingCredits,
                    color: .orange,
                    icon: "clock"
                )

                balanceBreakdown(
                    title: "本周收益",
                    value: walletData.weeklyEarnings,
                    color: .blue,
                    icon: "chart.line.uptrend.xyaxis"
                )
            }
        }
        .padding(.vertical, 24)
        .padding(.horizontal, 20)
        .background(
            RoundedRectangle(cornerRadius: 20)
                .fill(Color(.secondarySystemBackground))
                .shadow(color: Color.black.opacity(0.1), radius: 10, x: 0, y: 4)
        )
    }

    private func balanceBreakdown(title: String, value: Int, color: Color, icon: String) -> some View {
        VStack(spacing: 6) {
            Image(systemName: icon)
                .font(.title3)
                .foregroundStyle(color)

            Text("\(value)")
                .font(.headline)
                .fontWeight(.semibold)
                .foregroundStyle(.primary)

            Text(title)
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
    }

    private var quickActionsSection: some View {
        HStack(spacing: 16) {
            quickActionButton(
                icon: "arrow.up.circle.fill",
                title: "发送",
                color: .blue
            ) {
                viewModel.showSendSheet = true
            }

            quickActionButton(
                icon: "arrow.down.circle.fill",
                title: "接收",
                color: .green
            ) {
                // Show receive sheet
            }

            quickActionButton(
                icon: "banknote.fill",
                title: "兑换",
                color: .orange
            ) {
                // Show exchange sheet
            }

            quickActionButton(
                icon: "clock.arrow.circlepath",
                title: "历史",
                color: .purple
            ) {
                // Show full history
            }
        }
    }

    private func quickActionButton(icon: String, title: String, color: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 8) {
                Image(systemName: icon)
                    .font(.system(size: 24))
                    .foregroundStyle(color)

                Text(title)
                    .font(.caption)
                    .foregroundStyle(.primary)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
            .background(
                RoundedRectangle(cornerRadius: 12)
                    .fill(Color(.secondarySystemBackground))
            )
        }
    }

    private var transactionsFilterSection: some View {
        Picker("交易类型", selection: $viewModel.transactionFilter) {
            Text("全部").tag(TransactionFilter.all)
            Text("已获得").tag(TransactionFilter.earned)
            Text("已支出").tag(TransactionFilter.spent)
            Text("转账").tag(TransactionFilter.transfers)
        }
        .pickerStyle(.segmented)
    }

    private func transactionsSection(transactions: [Transaction]) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("近期交易")
                    .font(.headline)

                Spacer()

                if !transactions.isEmpty {
                    Text("共 \(transactions.count) 笔")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            if transactions.isEmpty {
                Text("暂无交易记录")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .center)
                    .padding(.vertical, 32)
            } else {
                VStack(spacing: 0) {
                    ForEach(transactions) { transaction in
                        transactionRow(transaction: transaction)

                        if transaction.id != transactions.last?.id {
                            Divider()
                                .padding(.leading, 60)
                        }
                    }
                }
            }
        }
    }

    private func transactionRow(transaction: Transaction) -> some View {
        HStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(transaction.type.backgroundColor.opacity(0.15))
                    .frame(width: 40, height: 40)

                Image(systemName: transaction.type.icon)
                    .font(.system(size: 16))
                    .foregroundStyle(transaction.type.backgroundColor)
            }

            VStack(alignment: .leading, spacing: 4) {
                Text(transaction.title)
                    .font(.subheadline)
                    .fontWeight(.medium)

                Text(Self.dateFormatter.string(from: transaction.date))
                    .font(.caption)
                    .foregroundStyle(.secondary)

                if let note = transaction.note, !note.isEmpty {
                    Text(note)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }

            Spacer()

            VStack(alignment: .trailing, spacing: 2) {
                Text(transaction.amountText)
                    .font(.headline)
                    .foregroundStyle(transaction.type.backgroundColor)
                    .fontWeight(.semibold)

                if transaction.status != .completed {
                    Text(transaction.status.displayName)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .padding(.vertical, 12)
    }

    private var sendButton: some View {
        Button(action: {
            viewModel.showSendSheet = true
        }) {
            HStack(spacing: 8) {
                Image(systemName: "paperplane.fill")
                    .font(.system(size: 18))

                Text("发送积分")
                    .font(.headline)
                    .fontWeight(.semibold)
            }
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 16)
            .background(
                Capsule()
                    .fill(Color.accentColor)
            )
            .shadow(color: Color.accentColor.opacity(0.4), radius: 8, x: 0, y: 4)
        }
        .padding(.horizontal, 20)
        .padding(.bottom, 20)
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

    private var filteredTransactions: [Transaction] {
        viewModel.transactions.filter { transaction in
            switch viewModel.transactionFilter {
            case .all:
                return true
            case .earned:
                return transaction.type == .credit || transaction.type == .reward
            case .spent:
                return transaction.type == .debit || transaction.type == .penalty
            case .transfers:
                return transaction.type == .transfer
            }
        }
    }

    private func loadWalletData() async {
        await viewModel.loadWalletData()
    }

    // MARK: - Send Credits Sheet

    @ViewBuilder
    private var sendCreditsSheet: some View {
        NavigationStack {
            VStack(spacing: 24) {
                recipientPickerSection
                amountInputSection
                noteInputSection
                Spacer()
                confirmSendButton
            }
            .padding()
            .navigationTitle("发送积分")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") {
                        viewModel.showSendSheet = false
                    }
                }
            }
        }
    }

    private var recipientPickerSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("接收人")
                .font(.headline)

            TextField("输入用户名或手机号", text: $viewModel.recipientInput)
                .textFieldStyle(.roundedBorder)
                .autocapitalization(.none)

            if let suggestedUsers = viewModel.suggestedUsers, !suggestedUsers.isEmpty {
                Text("推荐联系人")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(.top, 4)

                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 12) {
                        ForEach(suggestedUsers) { user in
                            suggestedUserChip(user: user)
                        }
                    }
                }
            }
        }
    }

    private func suggestedUserChip(user: SuggestedUser) -> some View {
        Button(action: {
            viewModel.recipientInput = user.username
        }) {
            HStack(spacing: 8) {
                Text(user.avatarEmoji)
                    .font(.title2)

                VStack(alignment: .leading, spacing: 2) {
                    Text(user.username)
                        .font(.subheadline)
                        .fontWeight(.medium)

                    Text(user.displayName)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(
                RoundedRectangle(cornerRadius: 12)
                    .fill(Color(.secondarySystemBackground))
            )
        }
    }

    private var amountInputSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("金额")
                .font(.headline)

            TextField("0", text: $viewModel.amountInput)
                .textFieldStyle(.roundedBorder)
                .keyboardType(.numberPad)
                .font(.system(size: 32, weight: .bold))

            HStack(spacing: 16) {
                quickAmountButton(amount: 10)
                quickAmountButton(amount: 50)
                quickAmountButton(amount: 100)
                quickAmountButton(amount: 500)
            }
        }
    }

    private func quickAmountButton(amount: Int) -> some View {
        Button(action: {
            viewModel.amountInput = "\(amount)"
        }) {
            Text("\(amount)")
                .font(.subheadline)
                .fontWeight(.medium)
                .foregroundStyle(Color.accentColor)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .background(
                    Capsule()
                        .fill(Color.accentColor.opacity(0.15))
                )
        }
    }

    private var noteInputSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("备注（可选）")
                .font(.headline)

            TextField("添加备注...", text: $viewModel.noteInput, axis: .vertical)
                .textFieldStyle(.roundedBorder)
                .lineLimit(3...5)
        }
    }

    private var confirmSendButton: some View {
        Button(action: {
            Task {
                await viewModel.sendCredits()
            }
        }) {
            Text("发送 \(viewModel.amountInput) 积分")
                .font(.headline)
                .fontWeight(.semibold)
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 16)
                .background(
                    RoundedRectangle(cornerRadius: 12)
                        .fill(viewModel.isValidSendRequest ? Color.accentColor : Color.gray)
                )
        }
        .disabled(!viewModel.isValidSendRequest || viewModel.isSending)
    }
}

// MARK: - Wallet Data

struct WalletData: Codable {
    let availableCredits: Int
    let pendingCredits: Int
    let weeklyEarnings: Int
    let lastUpdated: Date?

    var totalCredits: Int {
        availableCredits + pendingCredits
    }

    enum CodingKeys: String, CodingKey {
        case availableCredits = "available_credits"
        case pendingCredits = "pending_credits"
        case weeklyEarnings = "weekly_earnings"
        case lastUpdated = "last_updated"
    }
}

// MARK: - Transaction
// TransactionType, TransactionStatus, and Transaction are defined in Models/TokenEconomy.swift

enum TransactionFilter: String, CaseIterable {
    case all = "all"
    case earned = "earned"
    case spent = "spent"
    case transfers = "transfers"
}

// MARK: - Suggested User

struct SuggestedUser: Identifiable {
    let id: String
    let username: String
    let displayName: String
    let avatarEmoji: String
}

// MARK: - Wallet View Model

@MainActor
final class WalletViewModel: ObservableObject {
    @Published var walletData: WalletData?
    @Published var transactions: [Transaction] = []
    @Published var isLoading: Bool = false
    @Published var errorMessage: String?
    @Published var transactionFilter: TransactionFilter = .all
    @Published var showSendSheet: Bool = false

    // Send credits
    @Published var recipientInput: String = ""
    @Published var amountInput: String = ""
    @Published var noteInput: String = ""
    @Published var suggestedUsers: [SuggestedUser]?
    @Published var isSending: Bool = false

    var isValidSendRequest: Bool {
        !recipientInput.isEmpty &&
        !amountInput.isEmpty &&
        Int(amountInput) ?? 0 > 0
    }

    func loadWalletData() async {
        isLoading = true
        defer { isLoading = false }

        do {
            // Simulate API call
            try await Task.sleep(nanoseconds: 500_000_000)

            walletData = WalletData(
                availableCredits: 2450,
                pendingCredits: 180,
                weeklyEarnings: 520,
                lastUpdated: Date()
            )

            transactions = generateMockTransactions()
            suggestedUsers = generateMockUsers()
        } catch {
            errorMessage = "加载钱包数据失败"
        }
    }

    func sendCredits() async {
        guard isValidSendRequest else { return }

        isSending = true
        defer { isSending = false }

        do {
            try await Task.sleep(nanoseconds: 1_000_000_000)

            // Add new transaction
            let newTransaction = Transaction(
                id: UUID().uuidString,
                type: .transfer,
                amount: Int(amountInput) ?? 0,
                description: "发送给 \(recipientInput)",
                counterpartyId: recipientInput,
                taskId: nil,
                createdAt: Date(),
                status: .completed
            )

            transactions.insert(newTransaction, at: 0)

            // Update balance
            if var walletData = walletData {
                walletData = WalletData(
                    availableCredits: max(0, walletData.availableCredits - (Int(amountInput) ?? 0)),
                    pendingCredits: walletData.pendingCredits,
                    weeklyEarnings: walletData.weeklyEarnings,
                    lastUpdated: Date()
                )
            }

            // Reset form
            recipientInput = ""
            amountInput = ""
            noteInput = ""
            showSendSheet = false
        } catch {
            errorMessage = "发送失败，请重试"
        }
    }

    private func generateMockTransactions() -> [Transaction] {
        [
            Transaction(
                id: "1",
                type: .reward,
                amount: 150,
                description: "任务奖励 - 街景拍摄",
                counterpartyId: nil,
                taskId: "task_1",
                createdAt: Date().addingTimeInterval(-3600),
                status: .completed
            ),
            Transaction(
                id: "2",
                type: .transfer,
                amount: 100,
                description: "发送给 @john_doe",
                counterpartyId: "john_doe",
                taskId: nil,
                createdAt: Date().addingTimeInterval(-86400),
                status: .completed
            ),
            Transaction(
                id: "3",
                type: .transfer,
                amount: 200,
                description: "接收自 @alice",
                counterpartyId: "alice",
                taskId: nil,
                createdAt: Date().addingTimeInterval(-172800),
                status: .completed
            ),
            Transaction(
                id: "4",
                type: .reward,
                amount: 80,
                description: "任务奖励 - 环境监测",
                counterpartyId: nil,
                taskId: "task_2",
                createdAt: Date().addingTimeInterval(-259200),
                status: .pending
            ),
            Transaction(
                id: "5",
                type: .debit,
                amount: 500,
                description: "兑换礼品卡",
                counterpartyId: nil,
                taskId: nil,
                createdAt: Date().addingTimeInterval(-345600),
                status: .completed
            ),
            Transaction(
                id: "6",
                type: .reward,
                amount: 120,
                description: "任务奖励 - 交通分析",
                counterpartyId: nil,
                taskId: "task_3",
                createdAt: Date().addingTimeInterval(-432000),
                status: .completed
            )
        ]
    }

    private func generateMockUsers() -> [SuggestedUser] {
        [
            SuggestedUser(id: "1", username: "john_doe", displayName: "John Doe", avatarEmoji: "👨"),
            SuggestedUser(id: "2", username: "alice", displayName: "Alice Chen", avatarEmoji: "👩"),
            SuggestedUser(id: "3", username: "bob_wang", displayName: "Bob Wang", avatarEmoji: "🧑"),
            SuggestedUser(id: "4", username: "emma_li", displayName: "Emma Li", avatarEmoji: "👩‍💼")
        ]
    }
}

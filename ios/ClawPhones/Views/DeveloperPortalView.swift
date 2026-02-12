//
//  DeveloperPortalView.swift
//  ClawPhones
//

import SwiftUI

struct DeveloperPortalView: View {
    @State private var selectedTab = 0
    @State private var apiKeys: [APIKey] = []
    @State private var webhooks: [Webhook] = []
    @State private var usageStats: DeveloperPortalUsageStats?
    @State private var isLoading = true
    @State private var showGenerateKeySheet = false
    @State private var showAddWebhookSheet = false
    @State private var showRevokeAlert = false
    @State private var revokeTarget: APIKey?
    @State private var errorMessage: String?
    @State private var showingError = false
    @State private var copiedToClipboard = false

    var body: some View {
        TabView(selection: $selectedTab) {
            apiKeysTab
                .tabItem {
                    Label("API Keys", systemImage: "key.fill")
                }
                .tag(0)

            webhooksTab
                .tabItem {
                    Label("Webhooks", systemImage: "link.circle.fill")
                }
                .tag(1)

            usageTab
                .tabItem {
                    Label("Usage", systemImage: "chart.bar.fill")
                }
                .tag(2)
        }
        .navigationTitle("Developer Portal")
        .task {
            await loadData()
        }
        .refreshable {
            await loadData()
        }
        .sheet(isPresented: $showGenerateKeySheet) {
            GenerateKeySheet(isPresented: $showGenerateKeySheet) {
                Task { await loadAPIKeys() }
            }
        }
        .sheet(isPresented: $showAddWebhookSheet) {
            AddWebhookSheet(isPresented: $showAddWebhookSheet) {
                Task { await loadWebhooks() }
            }
        }
        .alert("Revoke API Key", isPresented: $showRevokeAlert) {
            Button("Cancel", role: .cancel) {
                revokeTarget = nil
            }
            Button("Revoke", role: .destructive) {
                Task {
                    if let key = revokeTarget {
                        await revokeKey(key)
                    }
                }
            }
        } message: {
            if let key = revokeTarget {
                Text("Are you sure you want to revoke the API key \"\(key.name)\"? This action cannot be undone.")
            }
        }
        .alert("Error", isPresented: $showingError) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(errorMessage ?? "")
        }
        .overlay(alignment: .top) {
            if copiedToClipboard {
                Text("Copied to clipboard")
                    .font(.caption)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(.ultraThinMaterial)
                    .clipShape(Capsule())
                    .padding(.top, 8)
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .onAppear {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                            copiedToClipboard = false
                        }
                    }
            }
        }
    }

    private var apiKeysTab: some View {
        List {
            if isLoading && apiKeys.isEmpty {
                Section {
                    HStack {
                        Spacer()
                        ProgressView()
                        Spacer()
                    }
                }
            } else if apiKeys.isEmpty {
                Section {
                    VStack(spacing: 16) {
                        Image(systemName: "key.fill")
                            .font(.system(size: 48))
                            .foregroundStyle(.secondary)

                        Text("No API Keys")
                            .font(.headline)
                            .foregroundStyle(.secondary)

                        Text("Generate an API key to access the Claw Phones API")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)

                        Button("Generate API Key") {
                            showGenerateKeySheet = true
                        }
                        .buttonStyle(.borderedProminent)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 32)
                }
            } else {
                ForEach(apiKeys) { apiKey in
                    apiKeyRow(apiKey)
                }
                .onDelete { indexSet in
                    for index in indexSet {
                        revokeTarget = apiKeys[index]
                        showRevokeAlert = true
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button {
                    showGenerateKeySheet = true
                } label: {
                    Image(systemName: "plus")
                }
            }
        }
    }

    private var webhooksTab: some View {
        List {
            if isLoading && webhooks.isEmpty {
                Section {
                    HStack {
                        Spacer()
                        ProgressView()
                        Spacer()
                    }
                }
            } else if webhooks.isEmpty {
                Section {
                    VStack(spacing: 16) {
                        Image(systemName: "link.circle.fill")
                            .font(.system(size: 48))
                            .foregroundStyle(.secondary)

                        Text("No Webhooks")
                            .font(.headline)
                            .foregroundStyle(.secondary)

                        Text("Add webhooks to receive real-time notifications")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)

                        Button("Add Webhook") {
                            showAddWebhookSheet = true
                        }
                        .buttonStyle(.borderedProminent)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 32)
                }
            } else {
                ForEach(webhooks) { webhook in
                    webhookRow(webhook)
                }
                .onDelete { indexSet in
                    Task {
                        for index in indexSet {
                            await deleteWebhook(webhooks[index])
                        }
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button {
                    showAddWebhookSheet = true
                } label: {
                    Image(systemName: "plus")
                }
            }
        }
    }

    private var usageTab: some View {
        List {
            if let stats = usageStats {
                Section("Overview") {
                    HStack {
                        Label("Total Calls", systemImage: "chart.bar")
                        Spacer()
                        Text("\(stats.totalCalls, specifier: "%,d")")
                            .foregroundStyle(.secondary)
                    }

                    HStack {
                        Label("This Month", systemImage: "calendar")
                        Spacer()
                        Text("\(stats.monthlyCalls, specifier: "%,d")")
                            .foregroundStyle(.secondary)
                    }

                    HStack {
                        Label("Success Rate", systemImage: "checkmark.circle")
                        Spacer()
                        Text("\(Int(stats.successRate * 100))%")
                            .foregroundStyle(.green)
                    }
                }

                Section("Rate Limit") {
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text("API Calls")
                            Spacer()
                            Text("\(stats.currentCalls) / \(stats.rateLimit)")
                                .foregroundStyle(.secondary)
                        }

                        ProgressView(value: Double(stats.currentCalls), total: Double(stats.rateLimit))
                            .tint(stats.currentCalls >= stats.rateLimit ? .red : .blue)
                    }
                }

                Section("Endpoints") {
                    ForEach(stats.topEndpoints.prefix(5)) { endpoint in
                        HStack {
                            Text(endpoint.path)
                                .font(.system(.caption, design: .monospaced))
                            Spacer()
                            Text("\(endpoint.calls, specifier: "%,d")")
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            } else if isLoading {
                Section {
                    HStack {
                        Spacer()
                        ProgressView()
                        Spacer()
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
    }

    private func apiKeyRow(_ key: APIKey) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(key.name)
                    .font(.headline)

                Spacer()

                if key.lastUsedAt != nil {
                    Text("Active")
                        .font(.caption)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(.green.opacity(0.2))
                        .foregroundStyle(.green)
                        .clipShape(Capsule())
                } else {
                    Text("Never used")
                        .font(.caption)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(.gray.opacity(0.2))
                        .foregroundStyle(.secondary)
                        .clipShape(Capsule())
                }
            }

            Text(maskedKey(key.key))
                .font(.system(.body, design: .monospaced))
                .foregroundStyle(.secondary)

            HStack(spacing: 4) {
                Label(key.permissions.joined(separator: ", "), systemImage: "key")
                    .font(.caption)
                    .foregroundStyle(.secondary)

                Spacer()

                Button {
                    UIPasteboard.general.string = key.key
                    copiedToClipboard = true
                } label: {
                    Label("Copy", systemImage: "doc.on.doc")
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
            }
        }
        .padding(.vertical, 4)
    }

    private func webhookRow(_ webhook: Webhook) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(webhook.url)
                    .font(.subheadline)
                    .lineLimit(1)

                if webhook.isActive {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundStyle(.green)
                } else {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.red)
                }
            }

            HStack(spacing: 4) {
                Label(webhook.events.joined(separator: ", "), systemImage: "bell")
                    .font(.caption)
                    .foregroundStyle(.secondary)

                Spacer()

                Button("Test") {
                    Task {
                        await testWebhook(webhook)
                    }
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
            }

            if let lastTriggered = webhook.lastTriggered {
                HStack {
                    Label("Last triggered: \(dateFormatter.string(from: lastTriggered))", systemImage: "clock")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .padding(.vertical, 4)
    }

    private func maskedKey(_ key: String) -> String {
        let prefix = String(key.prefix(8))
        let suffix = String(key.suffix(4))
        return "\(prefix)••••••••••••\(suffix)"
    }

    private var dateFormatter: DateFormatter {
        let formatter = DateFormatter()
        formatter.dateStyle = .short
        formatter.timeStyle = .short
        return formatter
    }

    @MainActor
    private func loadData() async {
        isLoading = true
        async let keys = loadAPIKeys()
        async let hooks = loadWebhooks()
        async let stats = loadUsage()

        apiKeys = await keys
        webhooks = await hooks
        usageStats = await stats
        isLoading = false
    }

    private func loadAPIKeys() async -> [APIKey] {
        // TODO: Implement API call
        return []
    }

    private func loadWebhooks() async -> [Webhook] {
        // TODO: Implement API call
        return []
    }

    private func loadUsage() async -> DeveloperPortalUsageStats? {
        // TODO: Implement API call
        return nil
    }

    @MainActor
    private func revokeKey(_ key: APIKey) async {
        // TODO: Implement API call
        revokeTarget = nil
        await loadAPIKeys()
    }

    @MainActor
    private func deleteWebhook(_ webhook: Webhook) async {
        // TODO: Implement API call
        await loadWebhooks()
    }

    @MainActor
    private func testWebhook(_ webhook: Webhook) async {
        // TODO: Implement test call
    }
}

// MARK: - Generate Key Sheet

private struct GenerateKeySheet: View {
    @Environment(\.dismiss) private var dismiss
    @Binding var isPresented: Bool
    let onGenerate: () -> Void

    @State private var name = ""
    @State private var selectedPermissions = Set<Permission>()
    @State private var isGenerating = false
    @State private var errorMessage: String?
    @State private var showingError = false

    private let availablePermissions: [Permission] = [
        .read, .write, .admin, .webhook
    ]

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Key Name", text: $name)
                        .textInputAutocapitalization(.words)
                } header: {
                    Text("Details")
                } footer: {
                    Text("Give your API key a descriptive name to help you identify it.")
                }

                Section {
                    ForEach(availablePermissions, id: \.self) { permission in
                        Toggle(permission.displayName, isOn: Binding(
                            get: { selectedPermissions.contains(permission) },
                            set: { isSelected in
                                if isSelected {
                                    selectedPermissions.insert(permission)
                                } else {
                                    selectedPermissions.remove(permission)
                                }
                            }
                        ))
                    }
                } header: {
                    Text("Permissions")
                } footer: {
                    Text("Select what this key can access.")
                }
            }
            .navigationTitle("Generate API Key")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Cancel") {
                        dismiss()
                    }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Generate") {
                        Task {
                            await generateKey()
                        }
                    }
                    .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                             selectedPermissions.isEmpty ||
                             isGenerating)
                }
            }
            .alert("Error", isPresented: $showingError) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(errorMessage ?? "")
            }
            .overlay {
                if isGenerating {
                    ProgressView()
                }
            }
        }
    }

    @MainActor
    private func generateKey() async {
        let keyName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !keyName.isEmpty else { return }

        isGenerating = true
        defer { isGenerating = false }

        // TODO: Implement API call
        dismiss()
        onGenerate()
    }
}

// MARK: - Add Webhook Sheet

private struct AddWebhookSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Binding var isPresented: Bool
    let onAdd: () -> Void

    @State private var url = ""
    @State private var selectedEvents = Set<WebhookEvent>()
    @State private var isAdding = false
    @State private var errorMessage: String?
    @State private var showingError = false

    private let availableEvents: [WebhookEvent] = [
        .alertCreated, .alertUpdated, .alertResolved, .system
    ]

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Webhook URL", text: $url)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocapitalization(.none)
                } header: {
                    Text("Configuration")
                } footer: {
                    Text("Enter the URL where webhook events will be sent.")
                }

                Section {
                    ForEach(availableEvents, id: \.self) { event in
                        Toggle(event.displayName, isOn: Binding(
                            get: { selectedEvents.contains(event) },
                            set: { isSelected in
                                if isSelected {
                                    selectedEvents.insert(event)
                                } else {
                                    selectedEvents.remove(event)
                                }
                            }
                        ))
                    }
                } header: {
                    Text("Events")
                } footer: {
                    Text("Select which events should trigger this webhook.")
                }
            }
            .navigationTitle("Add Webhook")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Cancel") {
                        dismiss()
                    }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Add") {
                        Task {
                            await addWebhook()
                        }
                    }
                    .disabled(!isValidURL(url) ||
                             selectedEvents.isEmpty ||
                             isAdding)
                }
            }
            .alert("Error", isPresented: $showingError) {
                Button("OK", role: .cancel) {}
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

    private func isValidURL(_ string: String) -> Bool {
        guard let url = URL(string: string) else { return false }
        return url.scheme == "http" || url.scheme == "https"
    }

    @MainActor
    private func addWebhook() async {
        guard !url.isEmpty else { return }

        isAdding = true
        defer { isAdding = false }

        // TODO: Implement API call
        dismiss()
        onAdd()
    }
}

// MARK: - Models
// APIKey is defined in Models/DeveloperSDK.swift

struct Webhook: Identifiable, Codable {
    let id: String
    let url: String
    let events: [String]
    let isActive: Bool
    let createdAt: Date
    var lastTriggered: Date?
}

// DeveloperPortalUsageStats for DeveloperPortal (local to this view)
struct DeveloperPortalUsageStats: Codable {
    let totalCalls: Int
    let monthlyCalls: Int
    let currentCalls: Int
    let rateLimit: Int
    let successRate: Double
    let topEndpoints: [EndpointUsage]
}

struct EndpointUsage: Identifiable, Codable {
    let id = UUID()
    let path: String
    let calls: Int
}

enum Permission: String, CaseIterable, Codable {
    case read = "read"
    case write = "write"
    case admin = "admin"
    case webhook = "webhook"

    var displayName: String {
        switch self {
        case .read: return "Read Access"
        case .write: return "Write Access"
        case .admin: return "Admin Access"
        case .webhook: return "Webhook Access"
        }
    }
}

enum WebhookEvent: String, CaseIterable, Codable {
    case alertCreated = "alert.created"
    case alertUpdated = "alert.updated"
    case alertResolved = "alert.resolved"
    case system = "system"

    var displayName: String {
        switch self {
        case .alertCreated: return "Alert Created"
        case .alertUpdated: return "Alert Updated"
        case .alertResolved: return "Alert Resolved"
        case .system: return "System Events"
        }
    }
}

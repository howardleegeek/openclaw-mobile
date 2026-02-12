//
//  PerformanceService.swift
//  ClawPhones
//
//  Service for monitoring app performance, health checks, and metrics reporting
//

import Foundation
import Combine
import UIKit

// MARK: - Performance Service
class PerformanceService: ObservableObject {

    // MARK: - Published Properties
    @Published var currentMetrics: AppMetrics?
    @Published var syncStatus: SyncStatus?
    @Published var healthCheck: HealthCheck?
    @Published var isMonitoring = false

    // MARK: - Properties
    private var metricsTimer: Timer?
    private var healthCheckTimer: Timer?
    private var lastMetricsReport = Date()
    private var networkInCounter: Int64 = 0
    private var networkOutCounter: Int64 = 0
    private var frameDropCounter: Int = 0
    private var startupStartTime: Date?

    // Background URLSession for metric uploads
    private let backgroundSession: URLSession

    // Configuration
    private let metricsReportInterval: TimeInterval = 300  // 5 minutes
    private let healthCheckInterval: TimeInterval = 120    // 2 minutes
    private let monitoringInterval: TimeInterval = 60       // 1 minute

    // Base URLs
    private let baseURL: String
    private var validURL: URL {
        guard let url = URL(string: baseURL) else {
            fatalError("Invalid base URL configuration")
        }
        return url
    }

    // MARK: - Initialization
    init(baseURL: String = "https://api.clawphones.com") {
        self.baseURL = baseURL

        // Configure background URLSession
        let config = URLSessionConfiguration.background(withIdentifier: "com.clawphones.background-metrics")
        config.sharedContainerIdentifier = "group.com.clawphones.shared"
        config.isDiscretionary = false
        config.sessionSendsLaunchEvents = true
        self.backgroundSession = URLSession(configuration: config)

        setupStartupTracking()
    }

    // MARK: - Startup Tracking
    private func setupStartupTracking() {
        startupStartTime = Date()
    }

    func reportStartupComplete() {
        guard let startTime = startupStartTime else { return }
        let startupTime = Date().timeIntervalSince(startTime) * 1000  // Convert to ms
        startupStartTime = nil

        Task {
            let metrics = await collectMetrics()
            let startupMetrics = AppMetrics(
                startupTimeMs: startupTime,
                memoryUsageMB: metrics.memoryUsageMB,
                cpuUsagePercent: metrics.cpuUsagePercent,
                batteryDrainPerHour: metrics.batteryDrainPerHour,
                networkBytesIn: metrics.networkBytesIn,
                networkBytesOut: metrics.networkBytesOut,
                activeConnections: metrics.activeConnections,
                frameDropCount: metrics.frameDropCount,
                cacheHitRate: metrics.cacheHitRate,
                collectedAt: Date()
            )
            await MainActor.run {
                self.currentMetrics = startupMetrics
            }
        }
    }

    // MARK: - Metrics Collection
    func collectMetrics() async -> AppMetrics {
        let processInfo = ProcessInfo.processInfo
        let device = UIDevice.current

        // Memory usage
        var memoryUsage: Double = 0
        var info = mach_task_basic_info()
        var count = mach_msg_type_number_t(MemoryLayout<mach_task_basic_info>.size) / 4

        let kerr: kern_return_t = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: 1) {
                task_info(mach_task_self_, task_flavor_t(MACH_TASK_BASIC_INFO), $0, &count)
            }
        }

        if kerr == KERN_SUCCESS {
            memoryUsage = Double(info.resident_size) / 1024 / 1024  // Convert to MB
        }

        // CPU usage (simplified estimate)
        let cpuUsage = min(100, max(0, processInfo.processorInfo?.cpuUsage ?? 0))

        // Battery drain
        device.isBatteryMonitoringEnabled = true
        let batteryLevel = device.batteryLevel
        let batteryState = device.batteryState
        let batteryDrain = batteryState == .discharging ? batteryLevel * 12 : 0  // Rough estimate

        // Active connections (would integrate with URLSession monitoring)
        let activeConnections = URLSession.shared.getAllTasks.count

        // Cache hit rate (would integrate with cache manager)
        let cacheHitRate = 85.0  // Placeholder

        return AppMetrics(
            startupTimeMs: startupStartTime != nil ? Date().timeIntervalSince(startupStartTime!) * 1000 : 0,
            memoryUsageMB: memoryUsage,
            cpuUsagePercent: cpuUsage,
            batteryDrainPerHour: batteryDrain,
            networkBytesIn: networkInCounter,
            networkBytesOut: networkOutCounter,
            activeConnections: activeConnections,
            frameDropCount: frameDropCounter,
            cacheHitRate: cacheHitRate
        )
    }

    // MARK: - Health Checks
    func runHealthCheck() async -> HealthCheck {
        async let relay = checkRelayReachability()
        async let backend = checkBackendReachability()
        async let ws = checkWebSocketConnection()
        async let push = checkPushRegistration()

        let (relayResult, backendResult, wsResult, pushResult) = await (relay, backend, ws, push)

        // Calculate overall latency
        let latency = (relayResult.latency + backendResult.latency) / 2

        return HealthCheck(
            relayReachable: relayResult.reachable,
            backendReachable: backendResult.reachable,
            wsConnected: wsResult.connected,
            pushRegistered: pushResult.registered,
            latencyMs: latency,
            lastCheckedAt: Date(),
            relayLatencyMs: relayResult.latency,
            backendLatencyMs: backendResult.latency
        )
    }

    private func checkRelayReachability() async -> (reachable: Bool, latency: Double) {
        let startTime = Date()

        do {
            let url = validURL.appendingPathComponent("/v1/relay/ping")
            var request = URLRequest(url: url)
            request.timeoutInterval = 10
            request.httpMethod = "GET"

            let (_, response) = try await URLSession.shared.data(for: request)

            let latency = Date().timeIntervalSince(startTime) * 1000
            let reachable = (response as? HTTPURLResponse)?.statusCode == 200

            return (reachable, latency)
        } catch {
            return (false, Date().timeIntervalSince(startTime) * 1000)
        }
    }

    private func checkBackendReachability() async -> (reachable: Bool, latency: Double) {
        let startTime = Date()

        do {
            let url = validURL.appendingPathComponent("/v1/health")
            var request = URLRequest(url: url)
            request.timeoutInterval = 10
            request.httpMethod = "GET"

            let (_, response) = try await URLSession.shared.data(for: request)

            let latency = Date().timeIntervalSince(startTime) * 1000
            let reachable = (response as? HTTPURLResponse)?.statusCode == 200

            return (reachable, latency)
        } catch {
            return (false, Date().timeIntervalSince(startTime) * 1000)
        }
    }

    private func checkWebSocketConnection() async -> Bool {
        // Would integrate with WebSocketManager
        return false
    }

    private func checkPushRegistration() async -> Bool {
        // Would integrate with PushNotificationService
        return false
    }

    // MARK: - Sync Status
    func getSyncStatus() async -> SyncStatus {
        do {
            let url = validURL.appendingPathComponent("/v1/sync/status")
            var request = URLRequest(url: url)
            request.timeoutInterval = 15
            request.httpMethod = "GET"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")

            let (data, response) = try await URLSession.shared.data(for: request)

            guard let httpResponse = response as? HTTPURLResponse,
                  httpResponse.statusCode == 200 else {
                throw URLError(.badServerResponse)
            }

            return try JSONDecoder().decode(SyncStatus.self, from: data)
        } catch {
            return SyncStatus(
                lastSyncAt: nil,
                pendingUploads: 0,
                pendingDownloads: 0,
                conflictCount: 0,
                syncHealthy: false,
                syncState: .error,
                lastError: error.localizedDescription
            )
        }
    }

    // MARK: - Cache Optimization
    func optimizeCache() async {
        // Evict cache entries older than 24 hours
        let cache = URLCache.shared
        let cutoffDate = Date().addingTimeInterval(-86400)

        // Get all cached responses (simplified - would need access to cache internals)
        let currentMemoryUsage = cache.currentMemoryUsage
        let memoryCapacity = cache.memoryCapacity

        // If cache is over 80% full, evict older entries
        if currentMemoryUsage > (memoryCapacity * 8 / 10) {
            cache.removeAllCachedResponses()
            print("Cache optimization: Cleared cache to free memory")
        }
    }

    // MARK: - Metrics Reporting
    func reportMetrics(_ metrics: AppMetrics) async throws {
        let url = validURL.appendingPathComponent("/v1/metrics/report")

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.timeoutInterval = 30

        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        request.httpBody = try encoder.encode(metrics)

        let (_, response) = try await URLSession.shared.data(for: request)

        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 || httpResponse.statusCode == 202 else {
            throw URLError(.badServerResponse)
        }

        // Reset counters after successful report
        networkInCounter = 0
        networkOutCounter = 0
        frameDropCounter = 0
        lastMetricsReport = Date()
    }

    // MARK: - Metrics History
    func fetchMetricsHistory(from startDate: Date, to endDate: Date) async throws -> [AppMetrics] {
        let url = validURL.appendingPathComponent("/v1/metrics/history")

        var components = URLComponents(url: url, resolvingAgainstBaseURL: true)!
        components.queryItems = [
            URLQueryItem(name: "from", value: ISO8601DateFormatter().string(from: startDate)),
            URLQueryItem(name: "to", value: ISO8601DateFormatter().string(from: endDate))
        ]

        var request = URLRequest(url: components.url!)
        request.httpMethod = "GET"
        request.timeoutInterval = 30

        let (data, response) = try await URLSession.shared.data(for: request)

        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw URLError(.badServerResponse)
        }

        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return try decoder.decode([AppMetrics].self, from: data)
    }

    // MARK: - Performance Monitoring
    func enablePerformanceMonitoring() {
        guard !isMonitoring else { return }

        isMonitoring = true

        // Metrics collection timer (every 60 seconds)
        metricsTimer = Timer.scheduledTimer(withTimeInterval: monitoringInterval, repeats: true) { [weak self] _ in
            Task {
                await self?.updateMetrics()
            }
        }

        // Health check timer (every 2 minutes)
        healthCheckTimer = Timer.scheduledTimer(withTimeInterval: healthCheckInterval, repeats: true) { [weak self] _ in
            Task {
                await self?.updateHealthCheck()
            }
        }

        // Initial collection
        Task {
            await updateMetrics()
            await updateHealthCheck()
        }
    }

    func disablePerformanceMonitoring() {
        isMonitoring = false
        metricsTimer?.invalidate()
        metricsTimer = nil
        healthCheckTimer?.invalidate()
        healthCheckTimer = nil
    }

    // MARK: - Private Update Methods
    private func updateMetrics() async {
        let metrics = await collectMetrics()

        await MainActor.run {
            self.currentMetrics = metrics
        }

        // Report metrics if interval has passed
        if Date().timeIntervalSince(lastMetricsReport) >= metricsReportInterval {
            do {
                try await reportMetrics(metrics)
            } catch {
                print("Failed to report metrics: \(error)")
            }
        }

        // Periodically optimize cache
        if Int(Date().timeIntervalSince(lastMetricsReport)) % 900 == 0 {  // Every 15 minutes
            await optimizeCache()
        }
    }

    private func updateHealthCheck() async {
        let health = await runHealthCheck()
        let sync = await getSyncStatus()

        await MainActor.run {
            self.healthCheck = health
            self.syncStatus = sync
        }
    }

    // MARK: - Connectivity Check
    func checkConnectivity() async -> Bool {
        let health = await runHealthCheck()
        return health.backendReachable
    }

    // MARK: - Network Counters
    func trackNetworkIn(bytes: Int64) {
        networkInCounter += bytes
    }

    func trackNetworkOut(bytes: Int64) {
        networkOutCounter += bytes
    }

    func trackFrameDrop() {
        frameDropCounter += 1
    }

    // MARK: - Cleanup
    deinit {
        disablePerformanceMonitoring()
    }
}

// MARK: - Extensions
extension ProcessInfo {
    var processorInfo: Double? {
        var numInfo: processor_info_array_t?
        var numCpuInfo: mach_msg_type_number_t = 0

        let result = host_processor_info(mach_host_self(), PROCESSOR_CPU_LOAD_INFO, &numCpuInfo, &numInfo)

        guard result == KERN_SUCCESS else { return nil }

        var cpuLoadInfo = numInfo!.withMemoryRebound(to: processor_cpu_load_info.self, capacity: 1) {
            $0.pointee
        }

        var totalTicks = 0
        var idleTicks = 0

        for i in 0..<numCpuInfo {
            totalTicks += Int(cpuLoadInfo.cpu_ticks[i].user) +
                         Int(cpuLoadInfo.cpu_ticks[i].system) +
                         Int(cpuLoadInfo.cpu_ticks[i].idle) +
                         Int(cpuLoadInfo.cpu_ticks[i].nice)
            idleTicks += Int(cpuLoadInfo.cpu_ticks[i].idle)
        }

        vm_deallocate(mach_task_self_, vm_address_t(numInfo!), vm_size_t(numCpuInfo * MemoryLayout<integer_t>.size))

        guard totalTicks > 0 else { return nil }
        let usage = (1.0 - Double(idleTicks) / Double(totalTicks)) * 100
        return (usage)
    }
}

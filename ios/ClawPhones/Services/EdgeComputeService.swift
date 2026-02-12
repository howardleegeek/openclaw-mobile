//
//  EdgeComputeService.swift
//  ClawPhones
//
//  Service for managing edge compute jobs and executing them locally
//

import Foundation
import Combine
import os.log

#if canImport(Vision)
import Vision
#endif

#if canImport(NaturalLanguage)
import NaturalLanguage
#endif

#if canImport(Speech)
import Speech
#endif

import UIKit

/// Service for managing edge computing operations
@MainActor
class EdgeComputeService: ObservableObject {

    // MARK: - Published Properties

    @Published var isProcessing: Bool = false
    @Published var currentJob: ComputeJob?
    @Published var completedJobs: [ComputeJob] = []
    @Published var failedJobs: [ComputeJob] = []
    @Published var totalCreditsEarned: Int = 0
    @Published var nodeCapabilities: EdgeNodeCapabilities?
    @Published var isRegistered: Bool = false
    @Published var errorMessage: String?
    @Published var connectionStatus: ConnectionStatus = .disconnected

    // MARK: - Private Properties

    private let logger = Logger(subsystem: "com.clawphones.edgecompute", category: "EdgeComputeService")
    private var cancellables = Set<AnyCancellable>()

    private var baseURL: URL
    private var apiToken: String?

    // Auto-processing controls
    private var autoProcessingEnabled = true
    private var processingTask: Task<Void, Never>?
    private let minimumBatteryLevel: Double = 30.0
    private let maximumThermalState: ProcessInfo.ThermalState = .critical
    private let jobProcessingInterval: TimeInterval = 5.0

    // Job execution limits
    private let maxConcurrentJobs = 1
    private var currentJobCount = 0

    // Safety limits
    private let maxJobExecutionTime: TimeInterval = 300.0 // 5 minutes
    private let maxConsecutiveFailures = 3
    private var consecutiveFailures = 0
    private var cooldownUntil: Date?

    // MARK: - Types

    enum ConnectionStatus {
        case disconnected
        case connecting
        case connected
        case error(Error)
    }

    // MARK: - Initialization

    init(baseURL: String = "https://api.clawphones.com", apiToken: String? = nil) {
        self.baseURL = URL(string: baseURL)!
        self.apiToken = apiToken
        self.nodeCapabilities = EdgeNodeCapabilities.currentDeviceCapabilities()

        setupObservers()
    }

    // MARK: - Setup

    private func setupObservers() {
        // Monitor battery level
        NotificationCenter.default.publisher(for: UIDevice.batteryLevelDidChangeNotification)
            .sink { [weak self] _ in
                self?.checkProcessingEligibility()
            }
            .store(in: &cancellables)

        // Monitor thermal state
        NotificationCenter.default.publisher(for: ProcessInfo.thermalStateDidChangeNotification)
            .sink { [weak self] _ in
                self?.checkProcessingEligibility()
            }
            .store(in: &cancellables)
    }

    // MARK: - Registration

    /// Register this edge node with the server
    func register() async throws {
        guard let capabilities = nodeCapabilities else {
            logger.error("Node capabilities not initialized")
            throw EdgeComputeError.capabilitiesNotInitialized
        }

        connectionStatus = .connecting

        let endpoint = baseURL.appendingPathComponent("/v1/edge/register")
        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        if let token = apiToken {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        let body = try JSONEncoder().encode(capabilities)
        request.httpBody = body

        logger.info("Registering edge node: \(capabilities.deviceId)")

        do {
            let (data, response) = try await URLSession.shared.data(for: request)

            guard let httpResponse = response as? HTTPURLResponse else {
                throw EdgeComputeError.invalidResponse
            }

            switch httpResponse.statusCode {
            case 200...299:
                isRegistered = true
                connectionStatus = .connected
                logger.info("Successfully registered edge node")
            case 401:
                throw EdgeComputeError.unauthorized
            case 409:
                // Already registered, just mark as connected
                isRegistered = true
                connectionStatus = .connected
                logger.info("Node already registered")
            default:
                throw EdgeComputeError.serverError(httpResponse.statusCode)
            }
        } catch {
            connectionStatus = .error(error)
            throw error
        }
    }

    // MARK: - Job Fetching

    /// Fetch available jobs from the server
    func fetchJobs() async throws -> [ComputeJob] {
        guard isRegistered else {
            throw EdgeComputeError.notRegistered
        }

        let endpoint = baseURL.appendingPathComponent("/v1/edge/jobs")
        var request = URLRequest(url: endpoint)
        request.httpMethod = "GET"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        if let token = apiToken {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        // Add capabilities filter
        if let capabilities = nodeCapabilities {
            let supportedTypes = capabilities.supportedJobTypes.map { $0.rawValue }
            var components = URLComponents(url: endpoint, resolvingAgainstBaseURL: false)!
            components.queryItems = [
                URLQueryItem(name: "supported_types", value: supportedTypes.joined(separator: ","))
            ]
            request.url = components.url
        }

        let (data, response) = try await URLSession.shared.data(for: request)

        guard let httpResponse = response as? HTTPURLResponse else {
            throw EdgeComputeError.invalidResponse
        }

        guard httpResponse.statusCode == 200 else {
            throw EdgeComputeError.serverError(httpResponse.statusCode)
        }

        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let jobs = try decoder.decode([ComputeJob].self, from: data)

        logger.info("Fetched \(jobs.count) available jobs")
        return jobs
    }

    /// Claim a job from the server
    func claimJob(jobId: String) async throws -> ComputeJob {
        guard isRegistered else {
            throw EdgeComputeError.notRegistered
        }

        let endpoint = baseURL.appendingPathComponent("/v1/edge/jobs/\(jobId)/claim")
        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        if let token = apiToken {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        let (data, response) = try await URLSession.shared.data(for: request)

        guard let httpResponse = response as? HTTPURLResponse else {
            throw EdgeComputeError.invalidResponse
        }

        guard httpResponse.statusCode == 200 else {
            throw EdgeComputeError.serverError(httpResponse.statusCode)
        }

        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let job = try decoder.decode(ComputeJob.self, from: data)

        logger.info("Claimed job: \(job.id)")
        return job
    }

    // MARK: - Job Execution

    /// Execute a compute job based on its type
    func executeJob(_ job: ComputeJob) async throws -> ComputeResult {
        guard currentJobCount < maxConcurrentJobs else {
            throw EdgeComputeError.maxConcurrentJobsReached
        }

        currentJobCount += 1
        currentJob = job
        isProcessing = true
        defer {
            currentJobCount -= 1
            isProcessing = currentJobCount > 0
            if !isProcessing {
                currentJob = nil
            }
        }

        let startTime = Date()

        // Set timeout for job execution
        let timeout = job.maxDurationSec > 0 ? TimeInterval(job.maxDurationSec) : maxJobExecutionTime

        logger.info("Executing job \(job.id) of type \(job.jobType.displayName)")

        do {
            // Execute with timeout
            let result = try await withThrowingTaskGroup(of: ComputeResult.self) { group in
                group.addTask {
                    try await self.performJobExecution(job)
                }

                group.addTask {
                    try await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
                    throw EdgeComputeError.jobTimeout
                }

                let result = try await group.next()!
                group.cancelAll()
                return result
            }

            // Record successful execution
            consecutiveFailures = 0
            let executionTime = Date().timeIntervalSince(startTime)

            logger.info("Job \(job.id) completed in \(executionTime)s")

            // Add to completed jobs
            let completedJob = job
            completedJobs.append(completedJob)

            return result

        } catch {
            logger.error("Job \(job.id) failed: \(error.localizedDescription)")

            consecutiveFailures += 1
            let failedJob = job
            failedJobs.append(failedJob)

            // Create error result
            let executionTime = Date().timeIntervalSince(startTime)
            return ComputeResult(
                jobId: job.id,
                success: false,
                errorMessage: error.localizedDescription,
                executionTimeSec: executionTime,
                timestamp: Date()
            )

        }
    }

    /// Perform the actual job execution based on job type
    private func performJobExecution(_ job: ComputeJob) async throws -> ComputeResult {
        let startTime = Date()

        switch job.jobType {
        #if canImport(Vision)
        case .imageClassification:
            return try await executeImageClassification(job, startTime: startTime)
        case .objectDetection:
            return try await executeObjectDetection(job, startTime: startTime)
        case .ocr:
            return try await executeOCR(job, startTime: startTime)
        #endif

        #if canImport(NaturalLanguage)
        case .textEmbedding:
            return try await executeTextEmbedding(job, startTime: startTime)
        case .sentimentAnalysis:
            return try await executeSentimentAnalysis(job, startTime: startTime)
        #endif

        #if canImport(Speech)
        case .audioTranscription:
            return try await executeAudioTranscription(job, startTime: startTime)
        #endif
        }
    }

    #if canImport(Vision)

    /// Execute image classification using Vision framework
    private func executeImageClassification(_ job: ComputeJob, startTime: Date) async throws -> ComputeResult {
        guard let image = UIImage(data: job.inputData) else {
            throw EdgeComputeError.invalidInputData
        }

        guard let cgImage = image.cgImage else {
            throw EdgeComputeError.invalidInputData
        }

        return try await withCheckedThrowingContinuation { continuation in
            let request = VNClassifyImageRequest()

            let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])

            do {
                try handler.perform([request])

                guard let results = request.results else {
                    continuation.resume(returning: ComputeResult(
                        jobId: job.id,
                        success: false,
                        errorMessage: "No classification results",
                        executionTimeSec: Date().timeIntervalSince(startTime)
                    ))
                    return
                }

                let topResult = results.first
                let confidence = topResult?.confidence ?? 0.0
                let label = topResult?.identifier ?? "unknown"

                // Create structured output
                let structuredOutput: [String: Any] = [
                    "label": label,
                    "confidence": confidence,
                    "all_results": results.prefix(5).map { [
                        "label": $0.identifier,
                        "confidence": $0.confidence
                    ]}
                ]

                let result = ComputeResult(
                    jobId: job.id,
                    success: true,
                    resultData: label.data(using: .utf8),
                    confidence: confidence,
                    structuredOutput: structuredOutput,
                    executionTimeSec: Date().timeIntervalSince(startTime)
                )

                continuation.resume(returning: result)

            } catch {
                continuation.resume(throwing: error)
            }
        }
    }

    /// Execute object detection using Vision framework
    private func executeObjectDetection(_ job: ComputeJob, startTime: Date) async throws -> ComputeResult {
        guard let image = UIImage(data: job.inputData) else {
            throw EdgeComputeError.invalidInputData
        }

        guard let cgImage = image.cgImage else {
            throw EdgeComputeError.invalidInputData
        }

        return try await withCheckedThrowingContinuation { continuation in
            let request = VNRecognizeAnimalsRequest() // Can also use VNRecognizeObjectsRequest for custom models

            let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])

            do {
                try handler.perform([request])

                guard let results = request.results else {
                    continuation.resume(returning: ComputeResult(
                        jobId: job.id,
                        success: true,
                        confidence: 0.0,
                        structuredOutput: ["detected_objects": []],
                        executionTimeSec: Date().timeIntervalSince(startTime)
                    ))
                    return
                }

                let detectedObjects = results.map { observation in
                    [
                        "label": observation.identifier,
                        "confidence": observation.confidence,
                        "bounding_box": [
                            "x": observation.boundingBox.origin.x,
                            "y": observation.boundingBox.origin.y,
                            "width": observation.boundingBox.size.width,
                            "height": observation.boundingBox.size.height
                        ]
                    ]
                }

                let structuredOutput: [String: Any] = [
                    "detected_objects": detectedObjects,
                    "count": detectedObjects.count
                ]

                let result = ComputeResult(
                    jobId: job.id,
                    success: true,
                    confidence: results.first?.confidence ?? 0.0,
                    structuredOutput: structuredOutput,
                    executionTimeSec: Date().timeIntervalSince(startTime)
                )

                continuation.resume(returning: result)

            } catch {
                continuation.resume(throwing: error)
            }
        }
    }

    /// Execute OCR using Vision framework
    private func executeOCR(_ job: ComputeJob, startTime: Date) async throws -> ComputeResult {
        guard let image = UIImage(data: job.inputData) else {
            throw EdgeComputeError.invalidInputData
        }

        guard let cgImage = image.cgImage else {
            throw EdgeComputeError.invalidInputData
        }

        return try await withCheckedThrowingContinuation { continuation in
            let request = VNRecognizeTextRequest { request, error in
                if let error = error {
                    continuation.resume(throwing: error)
                    return
                }

                guard let observations = request.results as? [VNRecognizedTextObservation] else {
                    continuation.resume(returning: ComputeResult(
                        jobId: job.id,
                        success: true,
                        confidence: 0.0,
                        structuredOutput: ["text": ""],
                        executionTimeSec: Date().timeIntervalSince(startTime)
                    ))
                    return
                }

                let recognizedText = observations.compactMap { observation in
                    observation.topCandidates(1).first?.string
                }.joined(separator: "\n")

                let structuredOutput: [String: Any] = [
                    "text": recognizedText,
                    "confidence": observations.first?.topCandidates(1).first?.confidence ?? 0.0,
                    "line_count": observations.count
                ]

                let result = ComputeResult(
                    jobId: job.id,
                    success: true,
                    resultData: recognizedText.data(using: .utf8),
                    confidence: observations.first?.topCandidates(1).first?.confidence ?? 0.0,
                    structuredOutput: structuredOutput,
                    executionTimeSec: Date().timeIntervalSince(startTime)
                )

                continuation.resume(returning: result)
            }

            request.recognitionLevel = .accurate
            request.usesLanguageCorrection = true

            let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])

            do {
                try handler.perform([request])
            } catch {
                continuation.resume(throwing: error)
            }
        }
    }

    #endif

    #if canImport(NaturalLanguage)

    /// Execute text embedding using NaturalLanguage framework
    private func executeTextEmbedding(_ job: ComputeJob, startTime: Date) async throws -> ComputeResult {
        guard let text = String(data: job.inputData, encoding: .utf8) else {
            throw EdgeComputeError.invalidInputData
        }

        let embedding = NLWordEmbedding.wordEmbedding(for: .english)

        let vector = embedding?.vector(for: text) ?? [Double](repeating: 0.0, count: 300)

        let structuredOutput: [String: Any] = [
            "embedding": vector,
            "dimension": vector.count
        ]

        // Convert vector to JSON data
        let jsonData = try JSONSerialization.data(withJSONObject: vector)

        return ComputeResult(
            jobId: job.id,
            success: true,
            resultData: jsonData,
            confidence: 1.0,
            structuredOutput: structuredOutput,
            executionTimeSec: Date().timeIntervalSince(startTime)
        )
    }

    /// Execute sentiment analysis using NaturalLanguage framework
    private func executeSentimentAnalysis(_ job: ComputeJob, startTime: Date) async throws -> ComputeResult {
        guard let text = String(data: job.inputData, encoding: .utf8) else {
            throw EdgeComputeError.invalidInputData
        }

        let tagger = NLTagger(tagSchemes: [.sentimentScore])
        tagger.string = text

        let (sentiment, _) = tagger.tag(at: text.startIndex,
                                        unit: .paragraph,
                                        scheme: .sentimentScore)

        let score = Double(sentiment?.rawValue ?? "0") ?? 0.0

        let sentimentLabel: String
        if score > 0.3 {
            sentimentLabel = "positive"
        } else if score < -0.3 {
            sentimentLabel = "negative"
        } else {
            sentimentLabel = "neutral"
        }

        let structuredOutput: [String: Any] = [
            "sentiment": sentimentLabel,
            "score": score,
            "text_length": text.count
        ]

        return ComputeResult(
            jobId: job.id,
            success: true,
            resultData: sentimentLabel.data(using: .utf8),
            confidence: min(abs(score) + 0.5, 1.0),
            structuredOutput: structuredOutput,
            executionTimeSec: Date().timeIntervalSince(startTime)
        )
    }

    #endif

    #if canImport(Speech)

    /// Execute audio transcription using Speech framework
    private func executeAudioTranscription(_ job: ComputeJob, startTime: Date) async throws -> ComputeResult {
        // Note: Speech framework requires file URLs, so we need to write data to temp file
        let tempURL = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension("wav")

        try job.inputData.write(to: tempURL)

        defer {
            try? FileManager.default.removeItem(at: tempURL)
        }

        return try await withCheckedThrowingContinuation { continuation in
            let recognizer = SFSpeechRecognizer()
            let request = SFSpeechURLRecognitionRequest(url: tempURL)

            recognizer?.recognitionTask(with: request) { result, error in
                if let error = error {
                    continuation.resume(throwing: error)
                    return
                }

                guard let result = result, result.isFinal else {
                    return
                }

                let transcribedText = result.bestTranscription.formattedString
                let confidence = result.bestTranscription.segments.map { $0.confidence }.reduce(0, +) /
                                   Double(result.bestTranscription.segments.count)

                let structuredOutput: [String: Any] = [
                    "text": transcribedText,
                    "confidence": confidence,
                    "segment_count": result.bestTranscription.segments.count
                ]

                let computeResult = ComputeResult(
                    jobId: job.id,
                    success: true,
                    resultData: transcribedText.data(using: .utf8),
                    confidence: confidence,
                    structuredOutput: structuredOutput,
                    executionTimeSec: Date().timeIntervalSince(startTime)
                )

                continuation.resume(returning: computeResult)
            }
        }
    }

    #endif

    // MARK: - Job Submission

    /// Submit job result to the server
    func submitJobResult(_ result: ComputeResult) async throws {
        guard isRegistered else {
            throw EdgeComputeError.notRegistered
        }

        let endpoint = baseURL.appendingPathComponent("/v1/edge/jobs/\(result.jobId)/result")
        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        if let token = apiToken {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        let body = try encoder.encode(result)
        request.httpBody = body

        let (_, response) = try await URLSession.shared.data(for: request)

        guard let httpResponse = response as? HTTPURLResponse else {
            throw EdgeComputeError.invalidResponse
        }

        guard httpResponse.statusCode == 200 else {
            throw EdgeComputeError.serverError(httpResponse.statusCode)
        }

        // Update credits
        if result.success, let job = completedJobs.first(where: { $0.id == result.jobId }) {
            totalCreditsEarned += job.rewardCredits
            logger.info("Job result submitted successfully. Credits: \(job.rewardCredits)")
        }

        logger.info("Result for job \(result.jobId) submitted successfully")
    }

    // MARK: - Auto-Processing Loop

    /// Start the auto-processing loop
    func startAutoProcessing() {
        guard processingTask == nil else { return }

        autoProcessingEnabled = true
        processingTask = Task {
            await processingLoop()
        }

        logger.info("Auto-processing started")
    }

    /// Stop the auto-processing loop
    func stopAutoProcessing() {
        autoProcessingEnabled = false
        processingTask?.cancel()
        processingTask = nil

        logger.info("Auto-processing stopped")
    }

    /// Main processing loop
    private func processingLoop() async {
        while autoProcessingEnabled && !Task.isCancelled {
            do {
                // Check eligibility
                guard isEligibleForProcessing() else {
                    try await Task.sleep(nanoseconds: UInt64(jobProcessingInterval * 1_000_000_000))
                    continue
                }

                // Check for cooldown
                if let cooldown = cooldownUntil, Date() < cooldown {
                    logger.debug("In cooldown until \(cooldown)")
                    try await Task.sleep(nanoseconds: UInt64(jobProcessingInterval * 1_000_000_000))
                    continue
                }

                // Try to fetch and process a job
                let jobs = try await fetchJobs()

                guard let job = jobs.first else {
                    // No jobs available, wait
                    try await Task.sleep(nanoseconds: UInt64(jobProcessingInterval * 1_000_000_000))
                    continue
                }

                // Claim the job
                let claimedJob = try await claimJob(jobId: job.id)

                // Execute the job
                let result = try await executeJob(claimedJob)

                // Submit the result
                if result.success {
                    try await submitJobResult(result)
                }

            } catch {
                logger.error("Processing loop error: \(error.localizedDescription)")

                // Handle consecutive failures
                if consecutiveFailures >= maxConsecutiveFailures {
                    logger.warning("Max consecutive failures reached, entering cooldown")
                    cooldownUntil = Date().addingTimeInterval(60.0) // 1 minute cooldown
                }

                // Small delay on error
                try? await Task.sleep(nanoseconds: UInt64(jobProcessingInterval * 1_000_000_000))
            }
        }
    }

    // MARK: - Safety Checks

    /// Check if the device is eligible for job processing
    private func isEligibleForProcessing() -> Bool {
        // Check battery level
        UIDevice.current.isBatteryMonitoringEnabled = true
        let batteryLevel = UIDevice.current.batteryLevel * 100

        guard batteryLevel >= minimumBatteryLevel else {
            logger.debug("Battery level too low: \(batteryLevel)%")
            return false
        }

        // Check thermal state
        let thermalState = ProcessInfo.processInfo.thermalState

        guard thermalState.rawValue <= maximumThermalState.rawValue else {
            logger.debug("Thermal state too high: \(thermalState)")
            return false
        }

        // Check connection status
        guard case .connected = connectionStatus else {
            logger.debug("Not connected to server")
            return false
        }

        // Check if already processing max jobs
        guard currentJobCount < maxConcurrentJobs else {
            logger.debug("Max concurrent jobs reached")
            return false
        }

        return true
    }

    /// Check processing eligibility and update state
    private func checkProcessingEligibility() {
        let isEligible = isEligibleForProcessing()

        if !isEligible {
            // Pause processing if not eligible
            if isProcessing {
                logger.info("Pausing processing due to ineligibility")
                // Note: Current job will complete, but no new jobs will start
            }
        } else {
            logger.debug("Device is eligible for processing")
        }
    }

    // MARK: - Public Methods

    /// Toggle auto-processing on/off
    func toggleAutoProcessing() {
        if autoProcessingEnabled {
            stopAutoProcessing()
        } else {
            startAutoProcessing()
        }
    }

    /// Clear completed and failed job history
    func clearJobHistory() {
        completedJobs.removeAll()
        failedJobs.removeAll()
        logger.info("Job history cleared")
    }

    /// Get current statistics
    func getStatistics() -> JobStatistics {
        JobStatistics(
            totalCompleted: completedJobs.count,
            totalFailed: failedJobs.count,
            totalCreditsEarned: totalCreditsEarned,
            isProcessing: isProcessing,
            currentJobId: currentJob?.id,
            eligibility: isEligibleForProcessing(),
            consecutiveFailures: consecutiveFailures,
            cooldownUntil: cooldownUntil
        )
    }
}

// MARK: - Supporting Types

struct JobStatistics {
    let totalCompleted: Int
    let totalFailed: Int
    let totalCreditsEarned: Int
    let isProcessing: Bool
    let currentJobId: String?
    let eligibility: Bool
    let consecutiveFailures: Int
    let cooldownUntil: Date?
}

enum EdgeComputeError: LocalizedError {
    case notRegistered
    case unauthorized
    case invalidResponse
    case serverError(Int)
    case capabilitiesNotInitialized
    case invalidInputData
    case jobTimeout
    case maxConcurrentJobsReached
    case unsupportedJobType

    var errorDescription: String? {
        switch self {
        case .notRegistered:
            return "Edge node is not registered"
        case .unauthorized:
            return "Unauthorized access"
        case .invalidResponse:
            return "Invalid server response"
        case .serverError(let code):
            return "Server error: \(code)"
        case .capabilitiesNotInitialized:
            return "Node capabilities not initialized"
        case .invalidInputData:
            return "Invalid input data"
        case .jobTimeout:
            return "Job execution timed out"
        case .maxConcurrentJobsReached:
            return "Maximum concurrent jobs reached"
        case .unsupportedJobType:
            return "Unsupported job type"
        }
    }
}

//
//  ComputeJob.swift
//  ClawPhones
//
//  Models for edge computing jobs and results
//

import Foundation
import UIKit

/// Type of compute job that can be executed on the edge node
enum JobType: String, Codable, CaseIterable {
    case imageClassification = "image_classification"
    case textEmbedding = "text_embedding"
    case ocr = "ocr"
    case objectDetection = "object_detection"
    case sentimentAnalysis = "sentiment_analysis"
    case audioTranscription = "audio_transcription"

    var displayName: String {
        switch self {
        case .imageClassification: return "Image Classification"
        case .textEmbedding: return "Text Embedding"
        case .ocr: return "OCR"
        case .objectDetection: return "Object Detection"
        case .sentimentAnalysis: return "Sentiment Analysis"
        case .audioTranscription: return "Audio Transcription"
        }
    }
}

/// Current status of a compute job
enum JobStatus: String, Codable, CaseIterable {
    case pending = "pending"
    case claimed = "claimed"
    case executing = "executing"
    case completed = "completed"
    case failed = "failed"
    case cancelled = "cancelled"
    case timeout = "timeout"

    var displayName: String {
        switch self {
        case .pending: return "Pending"
        case .claimed: return "Claimed"
        case .executing: return "Executing"
        case .completed: return "Completed"
        case .failed: return "Failed"
        case .cancelled: return "Cancelled"
        case .timeout: return "Timeout"
        }
    }

    var isTerminal: Bool {
        return self == .completed || self == .failed || self == .cancelled || self == .timeout
    }
}

/// A compute job that can be claimed and executed on the edge node
struct ComputeJob: Codable, Identifiable {
    /// Unique identifier for the job
    let id: String

    /// Type of compute job to execute
    let jobType: JobType

    /// Current status of the job
    var status: JobStatus

    /// Input data for the job (base64 encoded for binary data)
    let inputData: Data

    /// Reward credits for completing this job
    let rewardCredits: Int

    /// Maximum allowed execution time in seconds
    let maxDurationSec: Int

    /// Optional metadata about the job
    let metadata: [String: String]?

    /// Timestamp when the job was created
    let createdAt: Date?

    /// Timestamp when the job was claimed (if applicable)
    var claimedAt: Date?

    /// Timestamp when the job was completed (if applicable)
    var completedAt: Date?

    /// Estimated resource requirements
    let estimatedMemoryMB: Int?
    let estimatedCPUPercent: Int?

    /// Priority level (higher = more important)
    let priority: Int

    enum CodingKeys: String, CodingKey {
        case id
        case jobType = "job_type"
        case status
        case inputData = "input_data"
        case rewardCredits = "reward_credits"
        case maxDurationSec = "max_duration_sec"
        case metadata
        case createdAt = "created_at"
        case claimedAt = "claimed_at"
        case completedAt = "completed_at"
        case estimatedMemoryMB = "estimated_memory_mb"
        case estimatedCPUPercent = "estimated_cpu_percent"
        case priority
    }

    /// Initialize with computed values if needed
    init(
        id: String,
        jobType: JobType,
        status: JobStatus,
        inputData: Data,
        rewardCredits: Int,
        maxDurationSec: Int,
        metadata: [String: String]? = nil,
        createdAt: Date? = nil,
        claimedAt: Date? = nil,
        completedAt: Date? = nil,
        estimatedMemoryMB: Int? = nil,
        estimatedCPUPercent: Int? = nil,
        priority: Int = 0
    ) {
        self.id = id
        self.jobType = jobType
        self.status = status
        self.inputData = inputData
        self.rewardCredits = rewardCredits
        self.maxDurationSec = maxDurationSec
        self.metadata = metadata
        self.createdAt = createdAt
        self.claimedAt = claimedAt
        self.completedAt = completedAt
        self.estimatedMemoryMB = estimatedMemoryMB
        self.estimatedCPUPercent = estimatedCPUPercent
        self.priority = priority
    }
}

/// Result of executing a compute job
struct ComputeResult: Codable {
    /// The job this result belongs to
    let jobId: String

    /// Whether the execution was successful
    let success: Bool

    /// The actual result data (format depends on jobType)
    let resultData: Data?

    /// Confidence score (0.0-1.0) if applicable
    let confidence: Double?

    /// Additional structured output based on job type
    let structuredOutput: [String: Any]?

    /// Error message if execution failed
    let errorMessage: String?

    /// Execution time in seconds
    let executionTimeSec: Double

    /// Peak memory usage during execution (MB)
    let peakMemoryMB: Double?

    /// Timestamp when result was generated
    let timestamp: Date

    enum CodingKeys: String, CodingKey {
        case jobId = "job_id"
        case success
        case resultData = "result_data"
        case confidence
        case structuredOutput = "structured_output"
        case errorMessage = "error_message"
        case executionTimeSec = "execution_time_sec"
        case peakMemoryMB = "peak_memory_mb"
        case timestamp
    }

    init(
        jobId: String,
        success: Bool,
        resultData: Data? = nil,
        confidence: Double? = nil,
        structuredOutput: [String: Any]? = nil,
        errorMessage: String? = nil,
        executionTimeSec: Double,
        peakMemoryMB: Double? = nil,
        timestamp: Date = Date()
    ) {
        self.jobId = jobId
        self.success = success
        self.resultData = resultData
        self.confidence = confidence
        self.structuredOutput = structuredOutput
        self.errorMessage = errorMessage
        self.executionTimeSec = executionTimeSec
        self.peakMemoryMB = peakMemoryMB
        self.timestamp = timestamp
    }

    // Custom Codable implementation for [String: Any]
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        jobId = try container.decode(String.self, forKey: .jobId)
        success = try container.decode(Bool.self, forKey: .success)
        resultData = try container.decodeIfPresent(Data.self, forKey: .resultData)
        confidence = try container.decodeIfPresent(Double.self, forKey: .confidence)
        errorMessage = try container.decodeIfPresent(String.self, forKey: .errorMessage)
        executionTimeSec = try container.decode(Double.self, forKey: .executionTimeSec)
        peakMemoryMB = try container.decodeIfPresent(Double.self, forKey: .peakMemoryMB)
        timestamp = try container.decode(Date.self, forKey: .timestamp)

        if let structuredOutputData = try container.decodeIfPresent(Data.self, forKey: .structuredOutput),
           let jsonObject = try? JSONSerialization.jsonObject(with: structuredOutputData),
           let dict = jsonObject as? [String: Any] {
            structuredOutput = dict
        } else {
            structuredOutput = nil
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(jobId, forKey: .jobId)
        try container.encode(success, forKey: .success)
        try container.encodeIfPresent(resultData, forKey: .resultData)
        try container.encodeIfPresent(confidence, forKey: .confidence)
        try container.encodeIfPresent(errorMessage, forKey: .errorMessage)
        try container.encode(executionTimeSec, forKey: .executionTimeSec)
        try container.encodeIfPresent(peakMemoryMB, forKey: .peakMemoryMB)
        try container.encode(timestamp, forKey: .timestamp)

        if let structuredOutput = structuredOutput,
           let jsonData = try? JSONSerialization.data(withJSONObject: structuredOutput) {
            try container.encode(jsonData, forKey: .structuredOutput)
        }
    }
}

/// Capabilities of an edge node for compute jobs
struct EdgeNodeCapabilities: Codable {
    /// Unique device identifier
    let deviceId: String

    /// Device model name
    let deviceModel: String

    /// Operating system version
    let osVersion: String

    /// Available compute frameworks
    let supportedFrameworks: [String]

    /// Supported job types
    let supportedJobTypes: [JobType]

    /// Total available RAM in MB
    let totalMemoryMB: Int

    /// Approximate CPU cores
    let cpuCores: Int

    /// Has Neural Engine
    let hasNeuralEngine: Bool

    /// Has GPU compute support
    let hasGPUSupport: Bool

    /// Preferred job types (ordered by performance)
    let preferredJobTypes: [JobType]

    /// Maximum concurrent jobs this node can handle
    let maxConcurrentJobs: Int

    /// Average completion time estimates per job type (seconds)
    let avgCompletionTime: [JobType: Double]

    /// Current availability status
    var isAvailable: Bool

    /// Last heartbeat timestamp
    var lastHeartbeat: Date?

    enum CodingKeys: String, CodingKey {
        case deviceId = "device_id"
        case deviceModel = "device_model"
        case osVersion = "os_version"
        case supportedFrameworks = "supported_frameworks"
        case supportedJobTypes = "supported_job_types"
        case totalMemoryMB = "total_memory_mb"
        case cpuCores = "cpu_cores"
        case hasNeuralEngine = "has_neural_engine"
        case hasGPUSupport = "has_gpu_support"
        case preferredJobTypes = "preferred_job_types"
        case maxConcurrentJobs = "max_concurrent_jobs"
        case avgCompletionTime = "avg_completion_time"
        case isAvailable = "is_available"
        case lastHeartbeat = "last_heartbeat"
    }

    init(
        deviceId: String,
        deviceModel: String,
        osVersion: String,
        supportedFrameworks: [String],
        supportedJobTypes: [JobType],
        totalMemoryMB: Int,
        cpuCores: Int,
        hasNeuralEngine: Bool,
        hasGPUSupport: Bool,
        preferredJobTypes: [JobType],
        maxConcurrentJobs: Int,
        avgCompletionTime: [JobType: Double],
        isAvailable: Bool = true,
        lastHeartbeat: Date? = nil
    ) {
        self.deviceId = deviceId
        self.deviceModel = deviceModel
        self.osVersion = osVersion
        self.supportedFrameworks = supportedFrameworks
        self.supportedJobTypes = supportedJobTypes
        self.totalMemoryMB = totalMemoryMB
        self.cpuCores = cpuCores
        self.hasNeuralEngine = hasNeuralEngine
        self.hasGPUSupport = hasGPUSupport
        self.preferredJobTypes = preferredJobTypes
        self.maxConcurrentJobs = maxConcurrentJobs
        self.avgCompletionTime = avgCompletionTime
        self.isAvailable = isAvailable
        self.lastHeartbeat = lastHeartbeat
    }

    // Custom Codable implementation to handle [JobType: Double] dictionary
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        deviceId = try container.decode(String.self, forKey: .deviceId)
        deviceModel = try container.decode(String.self, forKey: .deviceModel)
        osVersion = try container.decode(String.self, forKey: .osVersion)
        supportedFrameworks = try container.decode([String].self, forKey: .supportedFrameworks)
        supportedJobTypes = try container.decode([JobType].self, forKey: .supportedJobTypes)
        totalMemoryMB = try container.decode(Int.self, forKey: .totalMemoryMB)
        cpuCores = try container.decode(Int.self, forKey: .cpuCores)
        hasNeuralEngine = try container.decode(Bool.self, forKey: .hasNeuralEngine)
        hasGPUSupport = try container.decode(Bool.self, forKey: .hasGPUSupport)
        preferredJobTypes = try container.decode([JobType].self, forKey: .preferredJobTypes)
        maxConcurrentJobs = try container.decode(Int.self, forKey: .maxConcurrentJobs)
        isAvailable = try container.decode(Bool.self, forKey: .isAvailable)
        lastHeartbeat = try container.decodeIfPresent(Date.self, forKey: .lastHeartbeat)

        // Decode avgCompletionTime as [String: Double] then convert to [JobType: Double]
        let avgCompletionTimeStrings = try container.decode([String: Double].self, forKey: .avgCompletionTime)
        var tempDict: [JobType: Double] = [:]
        for (key, value) in avgCompletionTimeStrings {
            if let jobType = JobType(rawValue: key) {
                tempDict[jobType] = value
            }
        }
        avgCompletionTime = tempDict
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(deviceId, forKey: .deviceId)
        try container.encode(deviceModel, forKey: .deviceModel)
        try container.encode(osVersion, forKey: .osVersion)
        try container.encode(supportedFrameworks, forKey: .supportedFrameworks)
        try container.encode(supportedJobTypes, forKey: .supportedJobTypes)
        try container.encode(totalMemoryMB, forKey: .totalMemoryMB)
        try container.encode(cpuCores, forKey: .cpuCores)
        try container.encode(hasNeuralEngine, forKey: .hasNeuralEngine)
        try container.encode(hasGPUSupport, forKey: .hasGPUSupport)
        try container.encode(preferredJobTypes, forKey: .preferredJobTypes)
        try container.encode(maxConcurrentJobs, forKey: .maxConcurrentJobs)
        try container.encode(isAvailable, forKey: .isAvailable)
        try container.encodeIfPresent(lastHeartbeat, forKey: .lastHeartbeat)

        // Encode avgCompletionTime by converting [JobType: Double] to [String: Double]
        let avgCompletionTimeStrings = avgCompletionTime.reduce(into: [:]) { result, pair in
            result[pair.key.rawValue] = pair.value
        }
        try container.encode(avgCompletionTimeStrings, forKey: .avgCompletionTime)
    }

    /// Generate capabilities for the current device
    static func currentDeviceCapabilities() -> EdgeNodeCapabilities {
        let device = UIDevice.current
        let systemVersion = ProcessInfo.processInfo.operatingSystemVersionString
        let physicalMemory = ProcessInfo.processInfo.physicalMemory / (1024 * 1024) // MB
        let processorCount = ProcessInfo.processInfo.processorCount

        // Determine available frameworks
        var frameworks: [String] = []
        #if canImport(Vision)
        frameworks.append("Vision")
        #endif
        #if canImport(NaturalLanguage)
        frameworks.append("NaturalLanguage")
        #endif
        #if canImport(Speech)
        frameworks.append("Speech")
        #endif
        #if canImport(CoreML)
        frameworks.append("CoreML")
        #endif

        // Determine supported job types based on available frameworks
        var jobTypes: [JobType] = []
        var preferredTypes: [JobType] = []
        var completionTimes: [JobType: Double] = [:]

        #if canImport(Vision)
        jobTypes.append(contentsOf: [.imageClassification, .objectDetection, .ocr])
        preferredTypes.append(.imageClassification)
        completionTimes[.imageClassification] = 0.5
        completionTimes[.objectDetection] = 1.0
        completionTimes[.ocr] = 2.0
        #endif

        #if canImport(NaturalLanguage)
        jobTypes.append(contentsOf: [.textEmbedding, .sentimentAnalysis])
        preferredTypes.append(.sentimentAnalysis)
        completionTimes[.textEmbedding] = 0.3
        completionTimes[.sentimentAnalysis] = 0.2
        #endif

        #if canImport(Speech)
        jobTypes.append(.audioTranscription)
        preferredTypes.append(.audioTranscription)
        completionTimes[.audioTranscription] = 5.0
        #endif

        // Check for Neural Engine (simplified detection)
        let deviceModel = device.model
        let hasNeuralEngine = deviceModel.hasPrefix("iPhone1") ||
                             deviceModel.hasPrefix("iPhone1") ||
                             deviceModel.contains("12") ||
                             deviceModel.contains("13") ||
                             deviceModel.contains("14") ||
                             deviceModel.contains("15") ||
                             deviceModel.contains("16") ||
                             deviceModel.contains("iPad1") ||
                             deviceModel.contains("Mac")

        return EdgeNodeCapabilities(
            deviceId: device.identifierForVendor?.uuidString ?? UUID().uuidString,
            deviceModel: deviceModel,
            osVersion: systemVersion,
            supportedFrameworks: frameworks,
            supportedJobTypes: jobTypes,
            totalMemoryMB: Int(physicalMemory),
            cpuCores: processorCount,
            hasNeuralEngine: hasNeuralEngine,
            hasGPUSupport: true, // Assume GPU support for Apple Silicon
            preferredJobTypes: preferredTypes,
            maxConcurrentJobs: 1,
            avgCompletionTime: completionTimes,
            isAvailable: true,
            lastHeartbeat: Date()
        )
    }
}

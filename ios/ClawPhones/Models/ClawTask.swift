//
//  ClawTask.swift
//  ClawPhones
//
//  Created on 2026-02-11.
//

import Foundation
import CoreLocation

// MARK: - Task Types
enum TaskType: String, Codable, CaseIterable {
    case photoSurvey = "photo_survey"
    case monitoring = "monitoring"
    case environmental = "environmental"
    case traffic = "traffic"
    case retail = "retail"

    var displayName: String {
        switch self {
        case .photoSurvey: return "Photo Survey"
        case .monitoring: return "Monitoring"
        case .environmental: return "Environmental"
        case .traffic: return "Traffic Analysis"
        case .retail: return "Retail Audit"
        }
    }

    var icon: String {
        switch self {
        case .photoSurvey: return "camera.fill"
        case .monitoring: return "waveform.path"
        case .environmental: return "leaf.fill"
        case .traffic: return "car.fill"
        case .retail: return "storefront.fill"
        }
    }
}

// MARK: - Task Status
enum TaskStatus: String, Codable, CaseIterable {
    case available = "available"
    case assigned = "assigned"
    case inProgress = "in_progress"
    case completed = "completed"
    case expired = "expired"

    var displayName: String {
        switch self {
        case .available: return "Available"
        case .assigned: return "Assigned"
        case .inProgress: return "In Progress"
        case .completed: return "Completed"
        case .expired: return "Expired"
        }
    }

    var isActive: Bool {
        return self == .assigned || self == .inProgress
    }
}

// MARK: - Task Requirements
struct TaskRequirements: Codable {
    let equipment: [String]
    let durationMinutes: Int
    let skillLevel: String?
    let requiredPhotos: Int?
    let notes: String?

    enum CodingKeys: String, CodingKey {
        case equipment
        case durationMinutes = "duration_minutes"
        case skillLevel = "skill_level"
        case requiredPhotos = "required_photos"
        case notes
    }
}

// MARK: - Reward Structure
struct TaskReward: Codable {
    let credits: Int
    let bonusMultiplier: Double

    var totalCredits: Int {
        return Int(Double(credits) * bonusMultiplier)
    }

    enum CodingKeys: String, CodingKey {
        case credits
        case bonusMultiplier = "bonus_multiplier"
    }
}

// MARK: - Task Location
struct TaskLocation: Codable {
    let latitude: Double
    let longitude: Double
    let address: String?
    let radiusMeters: Int?

    var coordinate: CLLocationCoordinate2D {
        return CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }

    var region: CLCircularRegion {
        return CLCircularRegion(
            center: coordinate,
            radius: CLLocationDistance(radiusMeters ?? 100),
            identifier: "task_region"
        )
    }

    enum CodingKeys: String, CodingKey {
        case latitude
        case longitude
        case address
        case radiusMeters = "radius_meters"
    }
}

// MARK: - Task Schedule
struct TaskSchedule: Codable {
    let startTime: Date
    let endTime: Date
    let timeSlot: String?

    var duration: TimeInterval {
        return endTime.timeIntervalSince(startTime)
    }

    var isCurrentlyScheduled: Bool {
        let now = Date()
        return now >= startTime && now <= endTime
    }

    enum CodingKeys: String, CodingKey {
        case startTime = "start_time"
        case endTime = "end_time"
        case timeSlot = "time_slot"
    }
}

// MARK: - Task Dates
struct TaskDates: Codable {
    let postedDate: Date
    let assignedDate: Date?
    let completedDate: Date?
    let expiryDate: Date?

    enum CodingKeys: String, CodingKey {
        case postedDate = "posted_date"
        case assignedDate = "assigned_date"
        case completedDate = "completed_date"
        case expiryDate = "expiry_date"
    }
}

// MARK: - Main Claw Task
struct ClawTask: Codable, Identifiable {
    let id: String
    let title: String
    let description: String
    let taskType: TaskType
    let status: TaskStatus
    let requirements: TaskRequirements
    let reward: TaskReward
    let location: TaskLocation
    let schedule: TaskSchedule
    let dates: TaskDates

    var isAvailable: Bool {
        status == .available && Date() < dates.expiryDate!
    }

    var isAssignedToUser: Bool {
        status == .assigned || status == .inProgress
    }

    var canBeAccepted: Bool {
        isAvailable
    }

    enum CodingKeys: String, CodingKey {
        case id
        case title
        case description
        case taskType = "task_type"
        case status
        case requirements
        case reward
        case location
        case schedule
        case dates
    }
}

// MARK: - Task Result
struct TaskResult: Codable {
    let taskId: String
    let submittedAt: Date
    let data: [String: Any]
    let photos: [String]?
    let notes: String?
    let location: CLLocationCoordinate2D?

    var success: Bool {
        return data["success"] as? Bool ?? false
    }

    var errorMessage: String? {
        return data["error"] as? String
    }

    init(taskId: String, submittedAt: Date = Date(), data: [String: Any], photos: [String]? = nil, notes: String? = nil, location: CLLocationCoordinate2D? = nil) {
        self.taskId = taskId
        self.submittedAt = submittedAt
        self.data = data
        self.photos = photos
        self.notes = notes
        self.location = location
    }

    enum CodingKeys: String, CodingKey {
        case taskId = "task_id"
        case submittedAt = "submitted_at"
        case data
        case photos
        case notes
        case location
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(taskId, forKey: .taskId)
        try container.encode(submittedAt, forKey: .submittedAt)

        // Convert data dictionary to JSON string
        let jsonData = try JSONSerialization.data(withJSONObject: data)
        let jsonString = String(data: jsonData, encoding: .utf8) ?? "{}"
        try container.encode(jsonString, forKey: .data)

        try container.encodeIfPresent(photos, forKey: .photos)
        try container.encodeIfPresent(notes, forKey: .notes)

        if let location = location {
            let locationDict: [String: Double] = [
                "latitude": location.latitude,
                "longitude": location.longitude
            ]
            try container.encode(locationDict, forKey: .location)
        }
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        taskId = try container.decode(String.self, forKey: .taskId)
        submittedAt = try container.decode(Date.self, forKey: .submittedAt)
        photos = try container.decodeIfPresent([String].self, forKey: .photos)
        notes = try container.decodeIfPresent(String.self, forKey: .notes)

        // Parse data from JSON string
        let jsonString = try container.decode(String.self, forKey: .data)
        if let jsonData = jsonString.data(using: .utf8),
           let parsedData = try? JSONSerialization.jsonObject(with: jsonData) as? [String: Any] {
            data = parsedData
        } else {
            data = [:]
        }

        // Parse location
        if let locationDict = try? container.decode([String: Double].self, forKey: .location) {
            location = CLLocationCoordinate2D(
                latitude: locationDict["latitude"] ?? 0,
                longitude: locationDict["longitude"] ?? 0
            )
        } else {
            location = nil
        }
    }
}

// MARK: - Earnings Summary
struct EarningsSummary: Codable {
    let totalCredits: Int
    let todayCredits: Int
    let weekCredits: Int
    let monthCredits: Int
    let completedTasks: Int
    let activeStreak: Int
    let lastUpdated: Date?

    var averagePerTask: Int {
        return completedTasks > 0 ? totalCredits / completedTasks : 0
    }

    var isStreakActive: Bool {
        return activeStreak > 0
    }

    enum CodingKeys: String, CodingKey {
        case totalCredits = "total_credits"
        case todayCredits = "today_credits"
        case weekCredits = "week_credits"
        case monthCredits = "month_credits"
        case completedTasks = "completed_tasks"
        case activeStreak = "active_streak"
        case lastUpdated = "last_updated"
    }

    init(totalCredits: Int = 0, todayCredits: Int = 0, weekCredits: Int = 0, monthCredits: Int = 0, completedTasks: Int = 0, activeStreak: Int = 0, lastUpdated: Date? = nil) {
        self.totalCredits = totalCredits
        self.todayCredits = todayCredits
        self.weekCredits = weekCredits
        self.monthCredits = monthCredits
        self.completedTasks = completedTasks
        self.activeStreak = activeStreak
        self.lastUpdated = lastUpdated
    }
}

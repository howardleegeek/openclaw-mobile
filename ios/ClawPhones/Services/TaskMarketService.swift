//
//  TaskMarketService.swift
//  ClawPhones
//
//  Created on 2026-02-11.
//

import Foundation
import Combine
import CoreLocation

// MARK: - Service Error
enum TaskMarketError: Error, LocalizedError {
    case networkError(Error)
    case invalidResponse
    case taskNotFound(String)
    case taskUnavailable
    case maxTasksReached
    case unauthorized
    case serverError(Int)
    case decodingError
    case uploadFailed
    case locationRequired

    var errorDescription: String? {
        switch self {
        case .networkError(let error):
            return "Network error: \(error.localizedDescription)"
        case .invalidResponse:
            return "Invalid response from server"
        case .taskNotFound(let id):
            return "Task \(id) not found"
        case .taskUnavailable:
            return "Task is no longer available"
        case .maxTasksReached:
            return "Maximum of 3 concurrent tasks reached"
        case .unauthorized:
            return "Unauthorized access"
        case .serverError(let code):
            return "Server error: \(code)"
        case .decodingError:
            return "Failed to decode response"
        case .uploadFailed:
            return "Failed to upload task result"
        case .locationRequired:
            return "Location is required for this task"
        }
    }
}

// MARK: - API Response Wrapper
struct APIResponse<T: Codable>: Codable {
    let success: Bool
    let data: T?
    let message: String?
    let error: String?
}

// MARK: - Task Market Service
class TaskMarketService: ObservableObject {
    // MARK: - Published Properties
    @Published var availableTasks: [ClawTask] = []
    @Published var myTasks: [ClawTask] = []
    @Published var earnings: EarningsSummary = EarningsSummary()
    @Published var isLoading: Bool = false
    @Published var errorMessage: String?

    // MARK: - Constants
    private let maxConcurrentTasks = 3
    private let autoMatchInterval: TimeInterval = 300 // 5 minutes
    private let baseURL: String

    // MARK: - Private Properties
    private var cancellables = Set<AnyCancellable>()
    private var autoMatchTimer: Timer?
    private var currentLocation: CLLocationCoordinate2D?

    // MARK: - Singleton
    static let shared = TaskMarketService()

    // MARK: - Initialization
    private init(baseURL: String = "https://api.clawphones.com") {
        self.baseURL = baseURL
        setupAutoMatchTimer()
    }

    deinit {
        autoMatchTimer?.invalidate()
    }

    // MARK: - Public Methods

    /// Fetch available tasks from the server
    /// - Parameter location: Optional location for proximity-based filtering
    /// - Returns: Array of available tasks
    func fetchAvailableTasks(location: CLLocationCoordinate2D? = nil) async throws -> [ClawTask] {
        isLoading = true
        defer { isLoading = false }

        var urlComponents = URLComponents(string: "\(baseURL)/v1/tasks/available")

        if let location = location {
            urlComponents?.queryItems = [
                URLQueryItem(name: "lat", value: String(location.latitude)),
                URLQueryItem(name: "lon", value: String(location.longitude))
            ]
        }

        guard let url = urlComponents?.url else {
            throw TaskMarketError.invalidResponse
        }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        // Add authentication token if available
        if let token = AuthManager.shared.accessToken {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        do {
            let (data, response) = try await URLSession.shared.data(for: request)

            guard let httpResponse = response as? HTTPURLResponse else {
                throw TaskMarketError.invalidResponse
            }

            guard (200...299).contains(httpResponse.statusCode) else {
                throw TaskMarketError.serverError(httpResponse.statusCode)
            }

            let apiResponse = try JSONDecoder().decode(APIResponse<[ClawTask]>.self, from: data)

            guard let tasks = apiResponse.data else {
                throw TaskMarketError.decodingError
            }

            await MainActor.run {
                self.availableTasks = tasks
                self.errorMessage = nil
            }

            return tasks
        } catch let error as TaskMarketError {
            await MainActor.run {
                self.errorMessage = error.errorDescription
            }
            throw error
        } catch {
            await MainActor.run {
                self.errorMessage = error.localizedDescription
            }
            throw TaskMarketError.networkError(error)
        }
    }

    /// Accept a task
    /// - Parameter taskId: The ID of the task to accept
    /// - Returns: The accepted task
    func acceptTask(taskId: String) async throws -> ClawTask {
        // Check concurrent task limit
        let activeTaskCount = myTasks.filter { $0.status.isActive }.count
        guard activeTaskCount < maxConcurrentTasks else {
            throw TaskMarketError.maxTasksReached
        }

        isLoading = true
        defer { isLoading = false }

        guard let url = URL(string: "\(baseURL)/v1/tasks/\(taskId)/accept") else {
            throw TaskMarketError.invalidResponse
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        if let token = AuthManager.shared.accessToken {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        do {
            let (data, response) = try await URLSession.shared.data(for: request)

            guard let httpResponse = response as? HTTPURLResponse else {
                throw TaskMarketError.invalidResponse
            }

            switch httpResponse.statusCode {
            case 200...299:
                let apiResponse = try JSONDecoder().decode(APIResponse<ClawTask>.self, from: data)

                guard let task = apiResponse.data else {
                    throw TaskMarketError.decodingError
                }

                await MainActor.run {
                    // Remove from available if present
                    self.availableTasks.removeAll { $0.id == taskId }

                    // Add to my tasks
                    if let index = self.myTasks.firstIndex(where: { $0.id == taskId }) {
                        self.myTasks[index] = task
                    } else {
                        self.myTasks.append(task)
                    }

                    self.errorMessage = nil
                }

                return task

            case 409:
                throw TaskMarketError.taskUnavailable
            case 401:
                throw TaskMarketError.unauthorized
            default:
                throw TaskMarketError.serverError(httpResponse.statusCode)
            }
        } catch let error as TaskMarketError {
            await MainActor.run {
                self.errorMessage = error.errorDescription
            }
            throw error
        } catch {
            await MainActor.run {
                self.errorMessage = error.localizedDescription
            }
            throw TaskMarketError.networkError(error)
        }
    }

    /// Submit a task result with multipart/form-data
    /// - Parameters:
    ///   - taskId: The ID of the task
    ///   - result: The task result containing data, photos, notes
    ///   - photos: Optional array of photo URLs or data
    /// - Returns: Success status
    func submitTaskResult(
        taskId: String,
        result: TaskResult,
        photos: [Data]? = nil
    ) async throws -> Bool {
        isLoading = true
        defer { isLoading = false }

        guard let url = URL(string: "\(baseURL)/v1/tasks/\(taskId)/submit") else {
            throw TaskMarketError.invalidResponse
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"

        if let token = AuthManager.shared.accessToken {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        // Create multipart/form-data boundary
        let boundary = "Boundary-\(UUID().uuidString)"
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")

        var body = Data()

        // Add task result data
        if let jsonData = try? JSONSerialization.data(withJSONObject: result.data) {
            body.append("--\(boundary)\r\n".data(using: .utf8)!)
            body.append("Content-Disposition: form-data; name=\"data\"\r\n".data(using: .utf8)!)
            body.append("Content-Type: application/json\r\n\r\n".data(using: .utf8)!)
            body.append(jsonData)
            body.append("\r\n".data(using: .utf8)!)
        }

        // Add notes if present
        if let notes = result.notes {
            body.append("--\(boundary)\r\n".data(using: .utf8)!)
            body.append("Content-Disposition: form-data; name=\"notes\"\r\n\r\n".data(using: .utf8)!)
            body.append(notes.data(using: .utf8)!)
            body.append("\r\n".data(using: .utf8)!)
        }

        // Add location if present
        if let location = result.location {
            body.append("--\(boundary)\r\n".data(using: .utf8)!)
            body.append("Content-Disposition: form-data; name=\"latitude\"\r\n\r\n".data(using: .utf8)!)
            body.append(String(location.latitude).data(using: .utf8)!)
            body.append("\r\n".data(using: .utf8)!)

            body.append("--\(boundary)\r\n".data(using: .utf8)!)
            body.append("Content-Disposition: form-data; name=\"longitude\"\r\n\r\n".data(using: .utf8)!)
            body.append(String(location.longitude).data(using: .utf8)!)
            body.append("\r\n".data(using: .utf8)!)
        }

        // Add photos if present
        if let photos = photos {
            for (index, photoData) in photos.enumerated() {
                body.append("--\(boundary)\r\n".data(using: .utf8)!)
                body.append("Content-Disposition: form-data; name=\"photos\"; filename=\"photo_\(index).jpg\"\r\n".data(using: .utf8)!)
                body.append("Content-Type: image/jpeg\r\n\r\n".data(using: .utf8)!)
                body.append(photoData)
                body.append("\r\n".data(using: .utf8)!)
            }
        }

        body.append("--\(boundary)--\r\n".data(using: .utf8)!)
        request.httpBody = body

        do {
            let (data, response) = try await URLSession.shared.data(for: request)

            guard let httpResponse = response as? HTTPURLResponse else {
                throw TaskMarketError.invalidResponse
            }

            guard (200...299).contains(httpResponse.statusCode) else {
                throw TaskMarketError.serverError(httpResponse.statusCode)
            }

            let apiResponse = try JSONDecoder().decode(APIResponse<[String: Bool]>.self, from: data)

            let success = apiResponse.data?["success"] ?? false

            if success {
                await MainActor.run {
                    // Update task status in myTasks
                    if let index = self.myTasks.firstIndex(where: { $0.id == taskId }) {
                        var updatedTask = self.myTasks[index]
                        // Note: In a real implementation, the server would return the updated task
                        // Here we just remove it from active tasks or update status
                        self.myTasks.remove(at: index)
                    }
                    self.errorMessage = nil
                }
            }

            return success
        } catch let error as TaskMarketError {
            await MainActor.run {
                self.errorMessage = error.errorDescription
            }
            throw error
        } catch {
            await MainActor.run {
                self.errorMessage = error.localizedDescription
            }
            throw TaskMarketError.networkError(error)
        }
    }

    /// Fetch tasks assigned to the current user
    /// - Returns: Array of user's tasks
    func fetchMyTasks() async throws -> [ClawTask] {
        isLoading = true
        defer { isLoading = false }

        guard let url = URL(string: "\(baseURL)/v1/tasks/my") else {
            throw TaskMarketError.invalidResponse
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
                throw TaskMarketError.invalidResponse
            }

            guard (200...299).contains(httpResponse.statusCode) else {
                if httpResponse.statusCode == 401 {
                    throw TaskMarketError.unauthorized
                }
                throw TaskMarketError.serverError(httpResponse.statusCode)
            }

            let apiResponse = try JSONDecoder().decode(APIResponse<[ClawTask]>.self, from: data)

            guard let tasks = apiResponse.data else {
                throw TaskMarketError.decodingError
            }

            await MainActor.run {
                self.myTasks = tasks
                self.errorMessage = nil
            }

            return tasks
        } catch let error as TaskMarketError {
            await MainActor.run {
                self.errorMessage = error.errorDescription
            }
            throw error
        } catch {
            await MainActor.run {
                self.errorMessage = error.localizedDescription
            }
            throw TaskMarketError.networkError(error)
        }
    }

    /// Fetch earnings summary for the current user
    /// - Returns: Earnings summary
    func fetchEarnings() async throws -> EarningsSummary {
        isLoading = true
        defer { isLoading = false }

        guard let url = URL(string: "\(baseURL)/v1/tasks/earnings") else {
            throw TaskMarketError.invalidResponse
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
                throw TaskMarketError.invalidResponse
            }

            guard (200...299).contains(httpResponse.statusCode) else {
                if httpResponse.statusCode == 401 {
                    throw TaskMarketError.unauthorized
                }
                throw TaskMarketError.serverError(httpResponse.statusCode)
            }

            let apiResponse = try JSONDecoder().decode(APIResponse<EarningsSummary>.self, from: data)

            guard let earnings = apiResponse.data else {
                throw TaskMarketError.decodingError
            }

            await MainActor.run {
                self.earnings = earnings
                self.errorMessage = nil
            }

            return earnings
        } catch let error as TaskMarketError {
            await MainActor.run {
                self.errorMessage = error.errorDescription
            }
            throw error
        } catch {
            await MainActor.run {
                self.errorMessage = error.localizedDescription
            }
            throw TaskMarketError.networkError(error)
        }
    }

    // MARK: - Auto Match and Accept

    /// Enable auto-match functionality
    /// - Parameter location: User's current location
    func enableAutoMatch(location: CLLocationCoordinate2D) {
        self.currentLocation = location
        autoMatchTimer?.invalidate()
        autoMatchTimer = Timer.scheduledTimer(withTimeInterval: autoMatchInterval, repeats: true) { [weak self] _ in
            Task {
                await self?.autoMatchAndAccept()
            }
        }
    }

    /// Disable auto-match functionality
    func disableAutoMatch() {
        autoMatchTimer?.invalidate()
        autoMatchTimer = nil
        currentLocation = nil
    }

    /// Automatically find and accept suitable tasks
    private func autoMatchAndAccept() async {
        guard let location = currentLocation else {
            return
        }

        // Check if we can accept more tasks
        let activeTaskCount = myTasks.filter { $0.status.isActive }.count
        guard activeTaskCount < maxConcurrentTasks else {
            return
        }

        do {
            let tasks = try await fetchAvailableTasks(location: location)

            // Filter tasks that match criteria
            let suitableTasks = tasks.filter { task in
                // Task must be available and not already in my tasks
                task.isAvailable && !myTasks.contains { $0.id == task.id }

                // Additional matching criteria can be added here
                // E.g., proximity, task type preferences, skill level, etc.
            }

            // Accept the best matching task (first in sorted list)
            if let bestTask = suitableTasks.first {
                _ = try await acceptTask(taskId: bestTask.id)
            }
        } catch {
            // Silently fail for auto-match
            print("Auto-match failed: \(error.localizedDescription)")
        }
    }

    // MARK: - Task Execution

    /// Execute a task (main entry point for task processing)
    /// - Parameters:
    ///   - task: The task to execute
    ///   - location: User's location
    ///   - completion: Completion handler with task result
    func executeTask(
        task: ClawTask,
        location: CLLocationCoordinate2D? = nil,
        completion: @escaping (Result<TaskResult, TaskMarketError>) -> Void
    ) {
        Task {
            do {
                // Update task status to in progress
                await updateTaskStatus(taskId: task.id, status: .inProgress)

                // Execute task based on type
                let result = try await performTask(task, location: location)

                // Submit result
                _ = try await submitTaskResult(
                    taskId: task.id,
                    result: result
                )

                // Update task status to completed
                await updateTaskStatus(taskId: task.id, status: .completed)

                // Refresh earnings
                _ = try await fetchEarnings()

                await MainActor.run {
                    completion(.success(result))
                }
            } catch let error as TaskMarketError {
                await MainActor.run {
                    completion(.failure(error))
                }
            } catch {
                await MainActor.run {
                    completion(.failure(TaskMarketError.networkError(error)))
                }
            }
        }
    }

    /// Perform the actual task based on its type
    private func performTask(_ task: ClawTask, location: CLLocationCoordinate2D?) async throws -> TaskResult {
        var taskData: [String: Any] = [
            "task_type": task.taskType.rawValue,
            "started_at": ISO8601DateFormatter().string(from: Date())
        ]

        switch task.taskType {
        case .photoSurvey:
            // Photo survey task execution
            taskData["photos_taken"] = task.requirements.requiredPhotos ?? 0
            taskData["survey_location"] = [
                "lat": task.location.latitude,
                "lon": task.location.longitude
            ]

        case .monitoring:
            // Monitoring task execution
            taskData["monitoring_duration"] = task.requirements.durationMinutes
            taskData["readings"] = [
                "signal_strength": Int.random(in: -80...-40),
                "network_type": "5G",
                "ping_ms": Int.random(in: 10...100)
            ]

        case .environmental:
            // Environmental task execution
            taskData["environmental_data"] = [
                "air_quality_index": Int.random(in: 0...200),
                "temperature": Double.random(in: 15...35),
                "humidity": Int.random(in: 30...80)
            ]

        case .traffic:
            // Traffic analysis task execution
            taskData["traffic_data"] = [
                "vehicle_count": Int.random(in: 10...200),
                "average_speed": Double.random(in: 20...60),
                "congestion_level": ["low", "medium", "high"].randomElement() ?? "medium"
            ]

        case .retail:
            // Retail audit task execution
            taskData["audit_data"] = [
                "store_status": "open",
                "shelf_inventory_percentage": Int.random(in: 60...100),
                "customer_count": Int.random(in: 0...30)
            ]
        }

        taskData["completed_at"] = ISO8601DateFormatter().string(from: Date())
        taskData["success"] = true

        return TaskResult(
            taskId: task.id,
            submittedAt: Date(),
            data: taskData,
            location: location ?? task.location.coordinate
        )
    }

    /// Update task status locally
    private func updateTaskStatus(taskId: String, status: TaskStatus) async {
        await MainActor.run {
            if let index = myTasks.firstIndex(where: { $0.id == taskId }) {
                var updatedTask = myTasks[index]
                // In a real implementation, this would create a new task with updated status
                // For now, we'll just mark the task as completed
                if status == .completed {
                    myTasks.remove(at: index)
                }
            }
        }
    }

    // MARK: - Timer Setup

    private func setupAutoMatchTimer() {
        // Timer is initially inactive, started via enableAutoMatch()
    }
}

// MARK: - Auth Manager (Placeholder)
// In a real implementation, this would be a separate service
class AuthManager {
    static let shared = AuthManager()
    var accessToken: String? {
        return UserDefaults.standard.string(forKey: "access_token")
    }
}

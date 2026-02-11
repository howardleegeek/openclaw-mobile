//
//  RelayClient.swift
//  ClawPhones
//

import Foundation
import Network

final class RelayClient {
    static let shared = RelayClient()

    struct NodeRegistration: Codable, Hashable {
        let ok: Bool?
        let nodeID: String
        let token: String
        let ingestURL: String?

        enum CodingKeys: String, CodingKey {
            case ok
            case nodeID = "node_id"
            case token
            case ingestURL = "ingest_url"
        }
    }

    struct FrameUploadResponse: Codable, Hashable {
        let ok: Bool?
        let id: String?
        let cell: String?
        let previewURL: String?

        enum CodingKeys: String, CodingKey {
            case ok
            case id
            case cell
            case previewURL = "preview_url"
        }
    }

    enum RelayError: LocalizedError {
        case invalidRelayURL(String)
        case missingCredentials
        case invalidResponse
        case http(statusCode: Int, message: String)
        case invalidRegistrationPayload

        var errorDescription: String? {
            switch self {
            case .invalidRelayURL(let value):
                return "Invalid relay URL: \(value)"
            case .missingCredentials:
                return "Relay credentials are missing. Call register() first."
            case .invalidResponse:
                return "Relay returned an invalid response."
            case .http(let statusCode, let message):
                return "Relay HTTP \(statusCode): \(message)"
            case .invalidRegistrationPayload:
                return "Relay registration response missing node_id/token."
            }
        }

        var isUnsupportedEndpoint: Bool {
            if case .http(let statusCode, _) = self {
                return statusCode == 404 || statusCode == 405
            }
            return false
        }
    }

    private struct RegisterRequest: Codable {
        let name: String
        let capabilities: [String]
    }

    fileprivate struct FramePayload: Codable, Hashable {
        let nodeID: String
        let ts: Int
        let lat: Double
        let lon: Double
        let heading: Double?
        let jpegBase64: String

        enum CodingKeys: String, CodingKey {
            case nodeID = "node_id"
            case ts
            case lat
            case lon
            case heading
            case jpegBase64 = "jpeg_base64"
        }
    }

    private struct HeartbeatPayload: Codable {
        let nodeID: String
        let ts: Int

        enum CodingKeys: String, CodingKey {
            case nodeID = "node_id"
            case ts
        }
    }

    private struct LastLocation: Codable, Hashable {
        let lat: Double
        let lon: Double
        let heading: Double?
        let ts: Int
    }

    private static let defaultRelayURL = "http://localhost:8787"
    private static let heartbeatJpegBase64 = "/9j/4AAQSkZJRgABAQAAAQABAAD/2wCEAAkGBxISEhUTEhIVFhUVFRUVFRUVFRUWFxUVFRUXFhUVFRUYHSggGBolHRUVITEhJSkrLi4uFx8zODMtNygtLisBCgoKDg0OGhAQGi0lHyUtLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLf/AABEIABQAFAMBIgACEQEDEQH/xAAXAAEBAQEAAAAAAAAAAAAAAAAAAQID/8QAFxEBAQEBAAAAAAAAAAAAAAAAAQACEf/aAAwDAQACEAMQAAAB6AA//8QAFhEBAQEAAAAAAAAAAAAAAAAAABEh/9oACAEBAAEFAn//xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oACAEDAQE/AR//xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oACAECAQE/AR//xAAVEAEBAAAAAAAAAAAAAAAAAAABEP/aAAgBAQAGPwJf/8QAFhABAQEAAAAAAAAAAAAAAAAAARAR/9oACAEBAAE/Idf/xAAWEQEBAQAAAAAAAAAAAAAAAAABABH/2gAIAQMBAT8hP//EABYRAQEBAAAAAAAAAAAAAAAAAAEAIf/aAAgBAgEBPyGf/8QAFhABAQEAAAAAAAAAAAAAAAAAARAR/9oACAEBAAE/IZf/2Q=="

    private let relayURLDefaultsKey = "ai.clawphones.relay.url"
    private let lastLocationDefaultsKey = "ai.clawphones.relay.last_location"
    private let nodeIDKeychainKey = "relay_node_id"
    private let tokenKeychainKey = "relay_token"

    private let session: URLSession
    private let frameQueue = RelayFrameQueue(storageKey: "clawphones.pending_relay_frames")
    private let pathMonitor = NWPathMonitor()
    private let monitorQueue = DispatchQueue(label: "ai.clawphones.relay.network-monitor")
    private let heartbeatQueue = DispatchQueue(label: "ai.clawphones.relay.heartbeat")
    private let heartbeatInterval: TimeInterval = 5 * 60
    private var heartbeatTimer: DispatchSourceTimer?

    private let flushStateQueue = DispatchQueue(label: "ai.clawphones.relay.flush-state")
    private var isFlushing = false

    private init(session: URLSession = .shared) {
        self.session = session
        pathMonitor.pathUpdateHandler = { [weak self] path in
            guard path.status == .satisfied else { return }
            Task { await self?.flushPendingFrames() }
        }
        pathMonitor.start(queue: monitorQueue)
        startHeartbeatLoop()
    }

    deinit {
        pathMonitor.cancel()
        heartbeatTimer?.cancel()
        heartbeatTimer = nil
    }

    var relayURL: String {
        get {
            let raw = UserDefaults.standard.string(forKey: relayURLDefaultsKey) ?? ""
            return normalizedRelayURL(from: raw) ?? Self.defaultRelayURL
        }
        set {
            let normalized = normalizedRelayURL(from: newValue) ?? Self.defaultRelayURL
            UserDefaults.standard.set(normalized, forKey: relayURLDefaultsKey)
        }
    }

    var nodeID: String? {
        KeychainHelper.shared.read(key: nodeIDKeychainKey)
    }

    func register(name: String = "clawvision-ios", capabilities: [String] = ["frame", "gps"]) async throws -> NodeRegistration {
        let endpoint = try endpointURL(path: "/v1/nodes/register")
        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.addValue("application/json", forHTTPHeaderField: "Accept")
        request.addValue("application/json", forHTTPHeaderField: "Content-Type")

        let body = RegisterRequest(name: name, capabilities: capabilities)
        request.httpBody = try JSONEncoder().encode(body)

        let data = try await perform(request)
        let registration = try JSONDecoder().decode(NodeRegistration.self, from: data)
        guard !registration.nodeID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !registration.token.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw RelayError.invalidRegistrationPayload
        }

        saveCredentials(nodeID: registration.nodeID, token: registration.token)
        await flushPendingFrames()
        return registration
    }

    @discardableResult
    func uploadFrame(jpeg: Data, lat: Double, lon: Double, heading: Double? = nil) async throws -> FrameUploadResponse {
        let credentials = try requireCredentials()
        let payload = FramePayload(
            nodeID: credentials.nodeID,
            ts: Int(Date().timeIntervalSince1970),
            lat: lat,
            lon: lon,
            heading: heading,
            jpegBase64: jpeg.base64EncodedString()
        )

        lastLocation = LastLocation(lat: lat, lon: lon, heading: heading, ts: payload.ts)

        do {
            let response = try await postFrame(payload: payload, token: credentials.token)
            await flushPendingFrames()
            return response
        } catch {
            frameQueue.enqueue(payload: payload)
            throw error
        }
    }

    func heartbeat() async {
        do {
            let credentials = try requireCredentials()
            do {
                try await postHeartbeat(nodeID: credentials.nodeID, token: credentials.token)
                return
            } catch let relayError as RelayError where relayError.isUnsupportedEndpoint {
                await sendFallbackFrameHeartbeat(credentials: credentials)
            } catch {
                // Keep heartbeat best-effort to avoid interrupting callers.
            }
        } catch {
            // Missing credentials or malformed URL: ignore in best-effort heartbeat.
        }
    }

    func flushPendingFrames() async {
        guard beginFlush() else { return }
        defer { endFlush() }

        guard let token = relayToken, !token.isEmpty else { return }

        while let pending = frameQueue.nextPending() {
            frameQueue.markSending(id: pending.id)

            do {
                _ = try await postFrame(payload: pending.payload, token: token)
                frameQueue.remove(id: pending.id)
            } catch {
                let retries = frameQueue.incrementRetryCount(id: pending.id)
                if retries >= 3 {
                    frameQueue.markFailed(id: pending.id)
                } else {
                    frameQueue.markPending(id: pending.id)
                }
                break
            }
        }
    }

    private func sendFallbackFrameHeartbeat(credentials: (nodeID: String, token: String)) async {
        guard let location = lastLocation,
              let jpeg = Data(base64Encoded: Self.heartbeatJpegBase64) else {
            return
        }

        let payload = FramePayload(
            nodeID: credentials.nodeID,
            ts: Int(Date().timeIntervalSince1970),
            lat: location.lat,
            lon: location.lon,
            heading: location.heading,
            jpegBase64: jpeg.base64EncodedString()
        )

        do {
            _ = try await postFrame(payload: payload, token: credentials.token)
        } catch {
            frameQueue.enqueue(payload: payload)
        }
    }

    private func postHeartbeat(nodeID: String, token: String) async throws {
        let endpoint = try endpointURL(path: "/v1/nodes/heartbeat")
        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.addValue("application/json", forHTTPHeaderField: "Accept")
        request.addValue("application/json", forHTTPHeaderField: "Content-Type")
        request.addValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

        let body = HeartbeatPayload(nodeID: nodeID, ts: Int(Date().timeIntervalSince1970))
        request.httpBody = try JSONEncoder().encode(body)

        _ = try await perform(request)
    }

    private func postFrame(payload: FramePayload, token: String) async throws -> FrameUploadResponse {
        let endpoint = try endpointURL(path: "/v1/events/frame")
        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.addValue("application/json", forHTTPHeaderField: "Accept")
        request.addValue("application/json", forHTTPHeaderField: "Content-Type")
        request.addValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.httpBody = try JSONEncoder().encode(payload)

        let data = try await perform(request)
        return try JSONDecoder().decode(FrameUploadResponse.self, from: data)
    }

    private func perform(_ request: URLRequest) async throws -> Data {
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw RelayError.invalidResponse
        }

        guard (200 ..< 300).contains(http.statusCode) else {
            let message: String
            if let text = String(data: data, encoding: .utf8), !text.isEmpty {
                message = text
            } else {
                message = HTTPURLResponse.localizedString(forStatusCode: http.statusCode)
            }
            throw RelayError.http(statusCode: http.statusCode, message: message)
        }

        return data
    }

    private func endpointURL(path: String) throws -> URL {
        guard let base = normalizedRelayURL(from: relayURL),
              let url = URL(string: "\(base)\(path)") else {
            throw RelayError.invalidRelayURL(relayURL)
        }
        return url
    }

    private func normalizedRelayURL(from raw: String) -> String? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }

        var normalized = trimmed
        while normalized.hasSuffix("/") {
            normalized.removeLast()
        }

        guard let url = URL(string: normalized),
              let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https",
              url.host != nil else {
            return nil
        }

        return normalized
    }

    private func requireCredentials() throws -> (nodeID: String, token: String) {
        guard let nodeID = self.nodeID?.trimmingCharacters(in: .whitespacesAndNewlines),
              !nodeID.isEmpty,
              let token = relayToken?.trimmingCharacters(in: .whitespacesAndNewlines),
              !token.isEmpty else {
            throw RelayError.missingCredentials
        }

        return (nodeID: nodeID, token: token)
    }

    private var relayToken: String? {
        KeychainHelper.shared.read(key: tokenKeychainKey)
    }

    private func saveCredentials(nodeID: String, token: String) {
        _ = KeychainHelper.shared.write(key: nodeIDKeychainKey, value: nodeID)
        _ = KeychainHelper.shared.write(key: tokenKeychainKey, value: token)
    }

    private var lastLocation: LastLocation? {
        get {
            guard let data = UserDefaults.standard.data(forKey: lastLocationDefaultsKey) else {
                return nil
            }

            return try? JSONDecoder().decode(LastLocation.self, from: data)
        }
        set {
            guard let newValue else {
                UserDefaults.standard.removeObject(forKey: lastLocationDefaultsKey)
                return
            }

            if let data = try? JSONEncoder().encode(newValue) {
                UserDefaults.standard.set(data, forKey: lastLocationDefaultsKey)
            }
        }
    }

    private func beginFlush() -> Bool {
        flushStateQueue.sync {
            if isFlushing {
                return false
            }
            isFlushing = true
            return true
        }
    }

    private func endFlush() {
        flushStateQueue.sync {
            isFlushing = false
        }
    }

    private func startHeartbeatLoop() {
        heartbeatQueue.sync {
            guard heartbeatTimer == nil else { return }

            let timer = DispatchSource.makeTimerSource(queue: heartbeatQueue)
            timer.schedule(deadline: .now() + heartbeatInterval, repeating: heartbeatInterval)
            timer.setEventHandler { [weak self] in
                Task { await self?.heartbeat() }
            }
            timer.resume()
            heartbeatTimer = timer
        }
    }
}

private final class RelayFrameQueue {
    struct PendingFrame: Identifiable, Codable, Hashable {
        enum Status: String, Codable {
            case pending
            case sending
            case failed
        }

        let id: String
        let payload: RelayClient.FramePayload
        let createdAt: Int
        var status: Status
        var retryCount: Int
    }

    private let storageKey: String
    private let storeQueue = DispatchQueue(label: "ai.clawphones.pending-relay-frame-queue")
    private var frames: [PendingFrame]

    init(storageKey: String) {
        self.storageKey = storageKey
        self.frames = Self.loadFromStore(storageKey: storageKey)
    }

    func enqueue(payload: RelayClient.FramePayload) {
        storeQueue.sync {
            let pending = PendingFrame(
                id: UUID().uuidString,
                payload: payload,
                createdAt: Int(Date().timeIntervalSince1970),
                status: .pending,
                retryCount: 0
            )
            frames.append(pending)
            persistLocked()
        }
    }

    func nextPending() -> PendingFrame? {
        storeQueue.sync {
            let candidates = frames.filter { $0.status == .pending || $0.status == .sending }
            return candidates.sorted { $0.createdAt < $1.createdAt }.first
        }
    }

    func markSending(id: String) {
        updateStatus(id: id, status: .sending)
    }

    func markPending(id: String) {
        updateStatus(id: id, status: .pending)
    }

    func markFailed(id: String) {
        updateStatus(id: id, status: .failed)
    }

    func incrementRetryCount(id: String) -> Int {
        storeQueue.sync {
            guard let index = frames.firstIndex(where: { $0.id == id }) else { return 0 }
            frames[index].retryCount += 1
            persistLocked()
            return frames[index].retryCount
        }
    }

    func remove(id: String) {
        storeQueue.sync {
            let before = frames.count
            frames.removeAll { $0.id == id }
            if frames.count != before {
                persistLocked()
            }
        }
    }

    private func updateStatus(id: String, status: PendingFrame.Status) {
        storeQueue.sync {
            guard let index = frames.firstIndex(where: { $0.id == id }) else { return }
            frames[index].status = status
            persistLocked()
        }
    }

    private func persistLocked() {
        do {
            let data = try JSONEncoder().encode(frames)
            UserDefaults.standard.set(data, forKey: storageKey)
        } catch {
            // Keep queue in memory even if persistence fails.
        }
    }

    private static func loadFromStore(storageKey: String) -> [PendingFrame] {
        guard let data = UserDefaults.standard.data(forKey: storageKey) else { return [] }
        do {
            return try JSONDecoder().decode([PendingFrame].self, from: data)
        } catch {
            return []
        }
    }
}

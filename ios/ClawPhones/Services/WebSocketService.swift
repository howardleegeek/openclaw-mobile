//
//  WebSocketService.swift
//  ClawPhones
//

import Foundation
import Combine

/// OkHttp-style WebSocket service using URLSessionWebSocketTask.
/// Provides automatic reconnection with exponential backoff and periodic ping/pong.
@MainActor
final class WebSocketService: ObservableObject {

    // MARK: - Published Properties

    @Published var isConnected: Bool = false

    // MARK: - Nested Types

    /// WebSocket message types
    enum Message {
        case text(String)
        case data(Data)
    }

    /// WebSocket errors
    enum WebSocketError: LocalizedError {
        case invalidURL(String)
        case connectionFailed(Error?)
        case notConnected
        case sendFailed(Error?)

        var errorDescription: String? {
            switch self {
            case .invalidURL(let value):
                return "Invalid WebSocket URL: \(value)"
            case .connectionFailed(let error):
                return "Connection failed: \(error?.localizedDescription ?? "Unknown error")"
            case .notConnected:
                return "WebSocket is not connected"
            case .sendFailed(let error):
                return "Send failed: \(error?.localizedDescription ?? "Unknown error")"
            }
        }
    }

    /// WebSocket event callbacks
    struct EventHandlers {
        var onMessage: ((Message) -> Void)?
        var onOpen: (() -> Void)?
        var onClose: ((Error?) -> Void)?
        var onClosing: (() -> Void)?
        var onFailure: ((Error) -> Void)?
    }

    // MARK: - Constants

    private static let pingInterval: TimeInterval = 30
    private static let initialBackoff: TimeInterval = 1
    private static let maxBackoff: TimeInterval = 30
    private static let backoffMultiplier: Double = 2

    // MARK: - Properties

    private var webSocketTask: URLSessionWebSocketTask?
    private var session: URLSession
    private var url: URL?
    private var token: String?

    private var eventHandlers: EventHandlers = EventHandlers()
    private var messageQueue: [Message] = []

    private var pingTimer: Timer?
    private var reconnectTimer: Timer?
    private var currentBackoff: TimeInterval = Self.initialBackoff
    private var shouldReconnect: Bool = true

    private let stateQueue = DispatchQueue(label: "ai.clawphones.websocket.state")
    private let messageQueueQueue = DispatchQueue(label: "ai.clawphones.websocket.message-queue")

    // MARK: - Initialization

    /// Initialize with optional URLSession configuration
    init(sessionConfiguration: URLSessionConfiguration = .default) {
        var config = sessionConfiguration
        config.timeoutIntervalForRequest = 30
        self.session = URLSession(configuration: config)
    }

    deinit {
        disconnect()
    }

    // MARK: - Public Methods

    /// Connect to WebSocket endpoint
    /// - Parameters:
    ///   - url: WebSocket URL (ws:// or wss://)
    ///   - token: Optional bearer token for authentication
    /// - Throws: WebSocketError if URL is invalid
    func connect(to url: String, token: String? = nil) throws {
        guard let websocketURL = URL(string: url),
              websocketURL.scheme == "ws" || websocketURL.scheme == "wss" else {
            throw WebSocketError.invalidURL(url)
        }

        // Disconnect existing connection if any
        disconnect()

        self.url = websocketURL
        self.token = token
        self.shouldReconnect = true
        self.currentBackoff = Self.initialBackoff

        performConnect()
    }

    /// Disconnect from WebSocket
    func disconnect() {
        shouldReconnect = false
        cancelReconnect()
        stopPing()

        stateQueue.async { [weak self] in
            guard let self else { return }
            self.webSocketTask?.cancel(with: .goingAway, reason: nil)
            self.webSocketTask = nil
            Task { @MainActor in
                self.isConnected = false
            }
        }
    }

    /// Send a message through WebSocket
    /// - Parameters:
    ///   - data: Message data to send
    /// - Throws: WebSocketError if not connected or send fails
    func sendMessage(_ data: Data) throws {
        send(.data(data))
    }

    /// Send a text message through WebSocket
    /// - Parameters:
    ///   - text: Text message to send
    /// - Throws: WebSocketError if not connected or send fails
    func sendMessage(_ text: String) throws {
        send(.text(text))
    }

    /// Set event handlers for WebSocket events
    /// - Parameters:
    ///   - onMessage: Callback when message is received
    ///   - onOpen: Callback when connection opens
    ///   - onClose: Callback when connection closes
    ///   - onClosing: Callback when connection is closing
    ///   - onFailure: Callback when error occurs
    func setHandlers(
        onMessage: ((Message) -> Void)? = nil,
        onOpen: (() -> Void)? = nil,
        onClose: ((Error?) -> Void)? = nil,
        onClosing: (() -> Void)? = nil,
        onFailure: ((Error) -> Void)? = nil
    ) {
        eventHandlers = EventHandlers(
            onMessage: onMessage,
            onOpen: onOpen,
            onClose: onClose,
            onClosing: onClosing,
            onFailure: onFailure
        )
    }

    // MARK: - Private Methods - Connection

    private func performConnect() {
        guard let url = url else { return }

        var request = URLRequest(url: url)
        request.timeoutInterval = 30

        if let token = token {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        stateQueue.async { [weak self] in
            guard let self else { return }

            self.webSocketTask = self.session.webSocketTask(with: request)
            self.webSocketTask?.resume()
            self.startPing()
            Task { @MainActor in
                self.isConnected = true
            }
            Task {
                await self.receiveMessageLoop()
            }
        }
    }

    private func scheduleReconnect() {
        guard shouldReconnect, url != nil else { return }

        cancelReconnect()

        reconnectTimer = Timer.scheduledTimer(
            withTimeInterval: currentBackoff,
            repeats: false
        ) { [weak self] _ in
            guard let self else { return }
            self.performConnect()
            // Exponential backoff capped at maxBackoff
            self.currentBackoff = min(
                self.currentBackoff * Self.backoffMultiplier,
                Self.maxBackoff
            )
        }
    }

    private func cancelReconnect() {
        reconnectTimer?.invalidate()
        reconnectTimer = nil
    }

    // MARK: - Private Methods - Send/Receive

    private func send(_ message: Message) throws {
        guard isConnected else {
            throw WebSocketError.notConnected
        }

        messageQueueQueue.async { [weak self] in
            guard let self else { return }

            self.messageQueue.append(message)
            self.flushMessageQueue()
        }
    }

    private func flushMessageQueue() {
        guard !messageQueue.isEmpty, let task = webSocketTask else { return }

        let message = messageQueue.removeFirst()

        Task {
            do {
                switch message {
                case .text(let text):
                    try await task.send(.string(text))
                case .data(let data):
                    try await task.send(.data(data))
                }
            } catch {
                messageQueueQueue.async { [weak self] in
                    self?.messageQueue.insert(message, at: 0)
                }
                Task { @MainActor [weak self] in
                    self?.eventHandlers.onFailure?(WebSocketError.sendFailed(error))
                }
                if !isConnected {
                    self?.scheduleReconnect()
                }
            }

            // Continue flushing if more messages
            messageQueueQueue.async { [weak self] in
                guard !self.messageQueue.isEmpty else { return }
                self?.flushMessageQueue()
            }
        }
    }

    private func receiveMessageLoop() async {
        while let task = webSocketTask, isConnected {
            do {
                let result = try await task.receive()
                switch result {
                case .string(let text):
                    Task { @MainActor [weak self] in
                        self?.eventHandlers.onMessage?(.text(text))
                    }
                case .data(let data):
                    Task { @MainActor [weak self] in
                        self?.eventHandlers.onMessage?(.data(data))
                    }
                @unknown default:
                    break
                }
            } catch {
                await handleReceiveError(error)
                break
            }
        }
    }

    private func handleReceiveError(_ error: Error) async {
        // Check if task is cancelled or closed
        if let webSocketError = error as? URLSessionWebSocketTask.CloseCode {
            if webSocketError == .goingAway || webSocketError == .normalClosure {
                return
            }
        }

        stateQueue.async { [weak self] in
            guard let self else { return }
            self.webSocketTask = nil
        }

        Task { @MainActor [weak self] in
            self?.isConnected = false
            self?.eventHandlers.onFailure?(error)
        }

        if shouldReconnect {
            scheduleReconnect()
        }
    }

    // MARK: - Private Methods - Ping/Pong

    private func startPing() {
        stopPing()

        pingTimer = Timer.scheduledTimer(
            withTimeInterval: Self.pingInterval,
            repeats: true
        ) { [weak self] _ in
            guard let self,
                  self.isConnected,
                  let task = self.webSocketTask else {
                return
            }

            Task {
                do {
                    try await task.sendPing()
                } catch {
                    Task { @MainActor [weak self] in
                        self?.isConnected = false
                        self?.eventHandlers.onFailure?(error)
                    }
                    if self.shouldReconnect {
                        self.scheduleReconnect()
                    }
                }
            }
        }
    }

    private func stopPing() {
        pingTimer?.invalidate()
        pingTimer = nil
    }
}

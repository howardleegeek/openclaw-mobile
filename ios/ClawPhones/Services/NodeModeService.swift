//
//  NodeModeService.swift
//  ClawPhones
//

import AVFoundation
import CoreLocation
import Network
import UIKit

final class NodeModeService: NSObject, ObservableObject {
    enum CaptureFPS: Double, CaseIterable {
        case fps0_5 = 0.5
        case fps1 = 1.0
        case fps2 = 2.0

        var timerInterval: TimeInterval {
            1.0 / rawValue
        }
    }

    enum UploadMode {
        case unrestricted
        case wifiOnly
    }

    enum StopReason: String {
        case manual
        case cameraPermissionDenied
        case cameraUnavailable
        case cameraConfigurationFailed
        case batteryBelowThreshold
    }

    struct FramePayload {
        let jpegData: Data
        let timestamp: Date
        let location: CLLocation?
        let motionDetected: Bool
    }

    @Published private(set) var isRunning = false
    @Published private(set) var framesCaptureded = 0
    @Published private(set) var uptimeSeconds = 0
    @Published private(set) var eventsDetected = 0
    @Published private(set) var lastKnownLocation: CLLocation?
    @Published private(set) var isOnWiFi = false
    @Published private(set) var batteryLevel: Float = -1
    @Published private(set) var lastStopReason: StopReason?

    var framesCaptured: Int { framesCaptureded }

    var captureFPS: CaptureFPS = .fps1 {
        didSet {
            guard captureFPS != oldValue else { return }
            restartCaptureTimerIfNeeded()
        }
    }

    var uploadMode: UploadMode = .unrestricted
    var batteryStopThreshold: Float = 0.15
    var motionSensitivity: MotionDetector.Sensitivity = .medium

    var onFrameCaptured: ((FramePayload) -> Void)?
    var onFrameReadyForUpload: ((FramePayload) -> Void)?

    private let captureSession = AVCaptureSession()
    private let photoOutput = AVCapturePhotoOutput()
    private let locationManager = CLLocationManager()
    private let pathMonitor = NWPathMonitor()
    private let motionDetector = MotionDetector()

    private let sessionQueue = DispatchQueue(label: "ai.clawphones.node-mode.capture")
    private let pathMonitorQueue = DispatchQueue(label: "ai.clawphones.node-mode.network")

    private var captureTimer: DispatchSourceTimer?
    private var uptimeTimer: DispatchSourceTimer?
    private var startDate: Date?
    private var previousFrameImage: UIImage?
    private var isSessionConfigured = false

    override init() {
        super.init()
        locationManager.delegate = self
        locationManager.desiredAccuracy = kCLLocationAccuracyBest
        locationManager.distanceFilter = kCLDistanceFilterNone
        locationManager.pausesLocationUpdatesAutomatically = false
        locationManager.allowsBackgroundLocationUpdates = true

        pathMonitor.pathUpdateHandler = { [weak self] path in
            let wifiConnected = path.status == .satisfied && path.usesInterfaceType(.wifi)
            DispatchQueue.main.async {
                self?.isOnWiFi = wifiConnected
            }
        }
        pathMonitor.start(queue: pathMonitorQueue)
    }

    deinit {
        pathMonitor.cancel()
        captureTimer?.cancel()
        uptimeTimer?.cancel()
    }

    func start() {
        guard !isRunning else { return }

        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            beginRunning()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    guard let self else { return }
                    if granted {
                        self.beginRunning()
                    } else {
                        self.lastStopReason = .cameraPermissionDenied
                    }
                }
            }
        case .denied, .restricted:
            lastStopReason = .cameraPermissionDenied
        @unknown default:
            lastStopReason = .cameraPermissionDenied
        }
    }

    func stop(reason: StopReason = .manual) {
        guard isRunning else {
            lastStopReason = reason
            return
        }

        isRunning = false
        lastStopReason = reason
        startDate = nil
        previousFrameImage = nil

        locationManager.stopUpdatingLocation()
        stopUptimeTimer()

        sessionQueue.async { [weak self] in
            guard let self else { return }
            self.captureTimer?.cancel()
            self.captureTimer = nil
            if self.captureSession.isRunning {
                self.captureSession.stopRunning()
            }
        }
    }

    private func beginRunning() {
        guard !isRunning else { return }

        isRunning = true
        framesCaptureded = 0
        uptimeSeconds = 0
        eventsDetected = 0
        lastStopReason = nil
        previousFrameImage = nil
        startDate = Date()

        UIDevice.current.isBatteryMonitoringEnabled = true
        refreshBatteryLevel()

        requestLocationAuthorizationIfNeeded()
        locationManager.startUpdatingLocation()
        startUptimeTimer()

        sessionQueue.async { [weak self] in
            guard let self else { return }
            do {
                try self.configureCaptureSessionIfNeeded()
                if !self.captureSession.isRunning {
                    self.captureSession.startRunning()
                }
                self.startCaptureTimerLocked()
            } catch let error as NodeModeError {
                self.handleSessionError(error)
            } catch {
                self.handleSessionError(.cameraConfigurationFailed)
            }
        }
    }

    private func configureCaptureSessionIfNeeded() throws {
        guard !isSessionConfigured else { return }

        captureSession.beginConfiguration()
        defer { captureSession.commitConfiguration() }
        captureSession.sessionPreset = .photo

        guard let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) else {
            throw NodeModeError.cameraUnavailable
        }

        let input: AVCaptureDeviceInput
        do {
            input = try AVCaptureDeviceInput(device: camera)
        } catch {
            throw NodeModeError.cameraConfigurationFailed
        }

        guard captureSession.canAddInput(input) else {
            throw NodeModeError.cameraConfigurationFailed
        }
        captureSession.addInput(input)

        guard captureSession.canAddOutput(photoOutput) else {
            throw NodeModeError.cameraConfigurationFailed
        }
        captureSession.addOutput(photoOutput)
        isSessionConfigured = true
    }

    private func startCaptureTimerLocked() {
        captureTimer?.cancel()

        let timer = DispatchSource.makeTimerSource(queue: sessionQueue)
        let interval = captureFPS.timerInterval
        timer.schedule(deadline: .now() + interval, repeating: interval)
        timer.setEventHandler { [weak self] in
            self?.captureTick()
        }
        timer.resume()
        captureTimer = timer
    }

    private func captureTick() {
        guard captureSession.isRunning else { return }

        if shouldAutoStopForBattery() {
            DispatchQueue.main.async { [weak self] in
                self?.stop(reason: .batteryBelowThreshold)
            }
            return
        }

        let settings = AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.jpeg])
        settings.flashMode = .off
        photoOutput.capturePhoto(with: settings, delegate: self)
    }

    private func shouldAutoStopForBattery() -> Bool {
        let level = UIDevice.current.batteryLevel
        DispatchQueue.main.async { [weak self] in
            self?.batteryLevel = level
        }

        guard level >= 0 else { return false }
        let threshold = min(max(batteryStopThreshold, 0), 1)
        return level < threshold
    }

    private func restartCaptureTimerIfNeeded() {
        guard isRunning else { return }
        sessionQueue.async { [weak self] in
            self?.startCaptureTimerLocked()
        }
    }

    private func startUptimeTimer() {
        stopUptimeTimer()

        let timer = DispatchSource.makeTimerSource(queue: .main)
        timer.schedule(deadline: .now() + 1, repeating: 1)
        timer.setEventHandler { [weak self] in
            guard let self, self.isRunning, let startDate = self.startDate else { return }
            self.uptimeSeconds = Int(Date().timeIntervalSince(startDate))
        }
        timer.resume()
        uptimeTimer = timer
    }

    private func stopUptimeTimer() {
        uptimeTimer?.cancel()
        uptimeTimer = nil
    }

    private func requestLocationAuthorizationIfNeeded() {
        switch locationManager.authorizationStatus {
        case .authorizedAlways:
            return
        case .authorizedWhenInUse:
            locationManager.requestAlwaysAuthorization()
        case .notDetermined:
            locationManager.requestAlwaysAuthorization()
        case .restricted, .denied:
            return
        @unknown default:
            return
        }
    }

    private func refreshBatteryLevel() {
        batteryLevel = UIDevice.current.batteryLevel
    }

    private func shouldUploadFrame() -> Bool {
        switch uploadMode {
        case .unrestricted:
            return true
        case .wifiOnly:
            return isOnWiFi
        }
    }

    private func normalizedJPEGData(from data: Data) -> Data? {
        guard let image = UIImage(data: data), image.size.width > 0, image.size.height > 0 else {
            return nil
        }

        let targetWidth: CGFloat = 640
        let scale = targetWidth / image.size.width
        let targetHeight = max(1, image.size.height * scale)
        let targetSize = CGSize(width: targetWidth, height: targetHeight)

        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        format.opaque = true

        let renderer = UIGraphicsImageRenderer(size: targetSize, format: format)
        let resized = renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: targetSize))
        }
        return resized.jpegData(compressionQuality: 0.6)
    }

    private func handleSessionError(_ error: NodeModeError) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            switch error {
            case .cameraUnavailable:
                self.stop(reason: .cameraUnavailable)
            case .cameraConfigurationFailed:
                self.stop(reason: .cameraConfigurationFailed)
            }
        }
    }

    private enum NodeModeError: Error {
        case cameraUnavailable
        case cameraConfigurationFailed
    }
}

extension NodeModeService: AVCapturePhotoCaptureDelegate {
    func photoOutput(
        _ output: AVCapturePhotoOutput,
        didFinishProcessingPhoto photo: AVCapturePhoto,
        error: Error?
    ) {
        guard error == nil,
              let rawData = photo.fileDataRepresentation(),
              let jpegData = normalizedJPEGData(from: rawData),
              let currentImage = UIImage(data: jpegData) else {
            return
        }

        let motionDetected: Bool
        if let previousFrameImage {
            motionDetected = motionDetector.hasMotion(
                previous: previousFrameImage,
                current: currentImage,
                sensitivity: motionSensitivity
            )
        } else {
            motionDetected = false
        }
        previousFrameImage = currentImage

        DispatchQueue.main.async { [weak self] in
            guard let self, self.isRunning else { return }

            self.framesCaptureded += 1
            if motionDetected {
                self.eventsDetected += 1
            }

            let payload = FramePayload(
                jpegData: jpegData,
                timestamp: Date(),
                location: self.lastKnownLocation,
                motionDetected: motionDetected
            )
            self.onFrameCaptured?(payload)

            if self.shouldUploadFrame() {
                self.onFrameReadyForUpload?(payload)
            }
        }
    }
}

extension NodeModeService: CLLocationManagerDelegate {
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        if isRunning, manager.authorizationStatus == .authorizedAlways {
            manager.startUpdatingLocation()
        }
    }

    func locationManager(_ manager: CLLocationManager, didChangeAuthorization status: CLAuthorizationStatus) {
        if isRunning, status == .authorizedAlways {
            manager.startUpdatingLocation()
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let latest = locations.last else { return }
        DispatchQueue.main.async { [weak self] in
            self?.lastKnownLocation = latest
        }
    }
}

//
//  VisionDetector.swift
//  ClawPhones
//
//  On-device object detection powered by Apple Vision.
//

import CoreGraphics
import Foundation
import Vision

final class VisionDetector {
    struct Detection {
        let type: DetectionType
        let confidence: Float
        let boundingBox: CGRect
    }

    enum DetectionType: String, Codable {
        case person
        case vehicle
        case animal
        case package
        case unknown
    }

    private let processingQueue = DispatchQueue(label: "ai.clawphones.vision.detector", qos: .userInitiated)
    private let vehicleKeywords: [String] = [
        "car", "truck", "bus", "van", "vehicle", "motorcycle", "bike", "bicycle",
        "train", "tram", "subway", "taxi", "suv", "boat", "ship", "airplane", "aircraft", "helicopter"
    ]
    private let packageKeywords: [String] = [
        "package", "parcel", "box", "cardboard", "crate", "delivery", "shipping", "mail", "envelope", "container"
    ]

    private(set) var confidenceThreshold: Float

    init(confidenceThreshold: Float = 0.5) {
        self.confidenceThreshold = Self.clampThreshold(confidenceThreshold)
    }

    func updateConfidenceThreshold(_ threshold: Float) {
        confidenceThreshold = Self.clampThreshold(threshold)
    }

    func detect(in image: CGImage) async -> [Detection] {
        await withCheckedContinuation { continuation in
            processingQueue.async {
                continuation.resume(returning: self.detectSync(in: image))
            }
        }
    }

    private func detectSync(in image: CGImage) -> [Detection] {
        var detections: [Detection] = []

        if #available(iOS 15.0, *) {
            detections.append(contentsOf: detectHumans(in: image))
            detections.append(contentsOf: detectAnimals(in: image))
        }

        detections.append(contentsOf: classifyVehicleAndPackage(in: image))
        return detections.sorted { $0.confidence > $1.confidence }
    }

    @available(iOS 15.0, *)
    private func detectHumans(in image: CGImage) -> [Detection] {
        let request = VNDetectHumanRectanglesRequest()
        request.maximumObservations = 32

        let handler = VNImageRequestHandler(cgImage: image, orientation: .up)
        do {
            try handler.perform([request])
        } catch {
            return []
        }

        return (request.results ?? []).compactMap { observation in
            let confidence = observation.confidence
            guard confidence >= confidenceThreshold else { return nil }
            return Detection(type: .person, confidence: confidence, boundingBox: observation.boundingBox)
        }
    }

    @available(iOS 15.0, *)
    private func detectAnimals(in image: CGImage) -> [Detection] {
        let request = VNRecognizeAnimalsRequest()

        let handler = VNImageRequestHandler(cgImage: image, orientation: .up)
        do {
            try handler.perform([request])
        } catch {
            return []
        }

        return (request.results ?? []).compactMap { observation in
            let confidence = observation.labels.first?.confidence ?? observation.confidence
            guard confidence >= confidenceThreshold else { return nil }
            return Detection(type: .animal, confidence: confidence, boundingBox: observation.boundingBox)
        }
    }

    private func classifyVehicleAndPackage(in image: CGImage) -> [Detection] {
        let request = VNClassifyImageRequest()
        let handler = VNImageRequestHandler(cgImage: image, orientation: .up)

        do {
            try handler.perform([request])
        } catch {
            return []
        }

        var bestConfidenceByType: [DetectionType: Float] = [:]

        for result in request.results ?? [] {
            let confidence = result.confidence
            guard confidence >= confidenceThreshold else { continue }

            let type = classifyType(for: result.identifier)
            guard type != .unknown else { continue }

            let currentBest = bestConfidenceByType[type] ?? 0
            if confidence > currentBest {
                bestConfidenceByType[type] = confidence
            }
        }

        let fullFrame = CGRect(x: 0, y: 0, width: 1, height: 1)
        return bestConfidenceByType.map { type, confidence in
            Detection(type: type, confidence: confidence, boundingBox: fullFrame)
        }
    }

    private func classifyType(for identifier: String) -> DetectionType {
        let lower = identifier.lowercased()

        if vehicleKeywords.contains(where: { lower.contains($0) }) {
            return .vehicle
        }

        if packageKeywords.contains(where: { lower.contains($0) }) {
            return .package
        }

        return .unknown
    }

    private static func clampThreshold(_ value: Float) -> Float {
        min(max(value, 0.0), 1.0)
    }
}

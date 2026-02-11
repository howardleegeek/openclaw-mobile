import ImageIO
import QuartzCore
import Vision
import XCTest
@testable import ClawPhones

final class VisionDetectorTests: XCTestCase {
    @available(iOS 15.0, *)
    func testDetectsPersonFromFixtureImage() async throws {
        let image = try loadFixture(named: "person_sample")
        try ensureVisionRuntimeAvailable(using: image)

        let detector = VisionDetector(confidenceThreshold: 0.5)
        let detections = await detector.detect(in: image)
        let personDetections = detections.filter { $0.type == .person }

        XCTAssertFalse(personDetections.isEmpty, "Expected at least one person detection from person_sample.jpg")
        XCTAssertGreaterThan(personDetections.map(\.confidence).max() ?? 0, 0.6, "Expected person confidence > 0.6")
        XCTAssertTrue(personDetections.allSatisfy { isNormalized($0.boundingBox) })
    }

    @available(iOS 15.0, *)
    func testDetectsAnimalFromFixtureImage() async throws {
        let image = try loadFixture(named: "animal_sample")
        try ensureVisionRuntimeAvailable(using: image)

        let detector = VisionDetector(confidenceThreshold: 0.5)
        let detections = await detector.detect(in: image)
        let animalDetections = detections.filter { $0.type == .animal }

        XCTAssertFalse(animalDetections.isEmpty, "Expected at least one animal detection from animal_sample.jpg")
        XCTAssertGreaterThan(animalDetections.map(\.confidence).max() ?? 0, 0.6, "Expected animal confidence > 0.6")
        XCTAssertTrue(animalDetections.allSatisfy { isNormalized($0.boundingBox) })
    }

    @available(iOS 15.0, *)
    func testHigherConfidenceThresholdFiltersOutDetections() async throws {
        let image = try loadFixture(named: "person_sample")
        try ensureVisionRuntimeAvailable(using: image)

        let lowThresholdDetector = VisionDetector(confidenceThreshold: 0.3)
        let highThresholdDetector = VisionDetector(confidenceThreshold: 0.9)

        let lowThresholdCount = await lowThresholdDetector.detect(in: image).count
        let highThresholdCount = await highThresholdDetector.detect(in: image).count

        XCTAssertLessThanOrEqual(highThresholdCount, lowThresholdCount)
    }

    @available(iOS 15.0, *)
    func testSingleFrameDetectionLatencyUnder200msOnDevice() async throws {
#if targetEnvironment(simulator)
        throw XCTSkip("Latency SLA is verified on physical iOS devices. Simulator timing is not stable.")
#else
        let image = try loadFixture(named: "person_sample")
        try ensureVisionRuntimeAvailable(using: image)

        let detector = VisionDetector(confidenceThreshold: 0.5)

        _ = await detector.detect(in: image)

        let start = CACurrentMediaTime()
        _ = await detector.detect(in: image)
        let elapsed = CACurrentMediaTime() - start

        XCTAssertLessThan(elapsed, 0.2, "Expected single frame detection latency < 200ms, got \(elapsed)s")
#endif
    }

    private func loadFixture(named name: String) throws -> CGImage {
        let bundle = Bundle(for: Self.self)
        guard let url = bundle.url(forResource: name, withExtension: "jpg", subdirectory: "Fixtures") else {
            throw XCTSkip("Missing fixture image: \(name).jpg")
        }

        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
              let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else {
            throw XCTSkip("Failed to decode fixture image: \(url.lastPathComponent)")
        }

        return image
    }

    private func ensureVisionRuntimeAvailable(using image: CGImage) throws {
        let request = VNClassifyImageRequest()
        let handler = VNImageRequestHandler(cgImage: image, orientation: .up)

        do {
            try handler.perform([request])
        } catch {
            throw XCTSkip("Vision runtime unavailable in this environment: \(error.localizedDescription)")
        }
    }

    private func isNormalized(_ rect: CGRect) -> Bool {
        rect.minX >= 0 && rect.minY >= 0 && rect.maxX <= 1 && rect.maxY <= 1
    }
}

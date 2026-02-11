//
//  MotionDetector.swift
//  ClawPhones
//

import CoreGraphics
import UIKit

final class MotionDetector {
    enum Sensitivity: String, CaseIterable {
        case low
        case medium
        case high

        // Percentage of changed pixels required to trigger motion.
        var changedPixelRatioThreshold: Double {
            switch self {
            case .low:
                return 0.15
            case .medium:
                return 0.08
            case .high:
                return 0.03
            }
        }
    }

    private let analysisWidth = 160
    private let analysisHeight = 120
    private let perPixelDiffThreshold: UInt8

    init(perPixelDiffThreshold: UInt8 = 25) {
        self.perPixelDiffThreshold = perPixelDiffThreshold
    }

    func hasMotion(
        previous: UIImage,
        current: UIImage,
        sensitivity: Sensitivity = .medium
    ) -> Bool {
        guard let previousPixels = grayscalePixels(from: previous),
              let currentPixels = grayscalePixels(from: current),
              previousPixels.count == currentPixels.count,
              !previousPixels.isEmpty else {
            return false
        }

        var changedPixelCount = 0

        for idx in 0 ..< previousPixels.count {
            let delta = abs(Int(previousPixels[idx]) - Int(currentPixels[idx]))
            if delta > Int(perPixelDiffThreshold) {
                changedPixelCount += 1
            }
        }

        let changedRatio = Double(changedPixelCount) / Double(previousPixels.count)
        return changedRatio > sensitivity.changedPixelRatioThreshold
    }

    private func grayscalePixels(from image: UIImage) -> [UInt8]? {
        guard let cgImage = makeCGImage(from: image) else {
            return nil
        }

        var buffer = [UInt8](repeating: 0, count: analysisWidth * analysisHeight)
        let colorSpace = CGColorSpaceCreateDeviceGray()

        guard let context = CGContext(
            data: &buffer,
            width: analysisWidth,
            height: analysisHeight,
            bitsPerComponent: 8,
            bytesPerRow: analysisWidth,
            space: colorSpace,
            bitmapInfo: CGImageAlphaInfo.none.rawValue
        ) else {
            return nil
        }

        context.interpolationQuality = .low
        context.draw(cgImage, in: CGRect(x: 0, y: 0, width: analysisWidth, height: analysisHeight))
        return buffer
    }

    private func makeCGImage(from image: UIImage) -> CGImage? {
        if let cgImage = image.cgImage {
            return cgImage
        }

        let targetSize = image.size
        guard targetSize.width > 0, targetSize.height > 0 else {
            return nil
        }

        let renderer = UIGraphicsImageRenderer(size: targetSize)
        let rendered = renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: targetSize))
        }
        return rendered.cgImage
    }
}

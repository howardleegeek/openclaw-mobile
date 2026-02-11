//
//  AlertEventStore.swift
//  ClawPhones
//

import Foundation
import CoreGraphics

actor AlertEventStore {
    static let shared = AlertEventStore()

    private static let retentionSeconds: TimeInterval = 7 * 24 * 60 * 60
    private static let maxEventCount = 1_000

    private let fileManager = FileManager.default
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder

    private struct StoredAlertEvent: Codable {
        let id: UUID
        let type: String
        let confidence: Float
        let timestamp: Date
        let latitude: Double
        let longitude: Double
        let boundingBox: CGRect?
    }

    private init() {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        encoder.dateEncodingStrategy = .millisecondsSince1970
        self.encoder = encoder

        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .millisecondsSince1970
        self.decoder = decoder
    }

    func loadEvents() -> [AlertEvent] {
        ensureDirectoriesIfNeeded()

        let metadata = prune(readMetadata())
        saveMetadata(metadata)
        cleanupOrphanedThumbnails(validIDs: Set(metadata.map(\.id)))

        return metadata.map { item in
            AlertEvent(
                id: item.id,
                type: item.type,
                confidence: item.confidence,
                timestamp: item.timestamp,
                latitude: item.latitude,
                longitude: item.longitude,
                thumbnailData: try? Data(contentsOf: thumbnailURL(for: item.id)),
                boundingBox: item.boundingBox
            )
        }
    }

    func addEvent(_ event: AlertEvent) {
        ensureDirectoriesIfNeeded()

        if let data = event.thumbnailData {
            try? data.write(to: thumbnailURL(for: event.id), options: .atomic)
        }

        var metadata = readMetadata()
        metadata.removeAll { $0.id == event.id }
        metadata.append(
            StoredAlertEvent(
                id: event.id,
                type: event.type,
                confidence: event.confidence,
                timestamp: event.timestamp,
                latitude: event.latitude,
                longitude: event.longitude,
                boundingBox: event.boundingBox
            )
        )

        let pruned = prune(metadata)
        saveMetadata(pruned)
        cleanupOrphanedThumbnails(validIDs: Set(pruned.map(\.id)))
    }

    func deleteEvent(id: UUID) {
        ensureDirectoriesIfNeeded()

        var metadata = readMetadata()
        metadata.removeAll { $0.id == id }
        saveMetadata(metadata)
        try? fileManager.removeItem(at: thumbnailURL(for: id))
    }

    private var baseDirectory: URL {
        let root = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return root.appendingPathComponent("clawvision_alerts", isDirectory: true)
    }

    private var metadataFileURL: URL {
        baseDirectory.appendingPathComponent("events.json")
    }

    private var thumbnailsDirectory: URL {
        baseDirectory.appendingPathComponent("thumbnails", isDirectory: true)
    }

    private func thumbnailURL(for id: UUID) -> URL {
        thumbnailsDirectory.appendingPathComponent("\(id.uuidString).jpg")
    }

    private func ensureDirectoriesIfNeeded() {
        if !fileManager.fileExists(atPath: baseDirectory.path) {
            try? fileManager.createDirectory(at: baseDirectory, withIntermediateDirectories: true)
        }
        if !fileManager.fileExists(atPath: thumbnailsDirectory.path) {
            try? fileManager.createDirectory(at: thumbnailsDirectory, withIntermediateDirectories: true)
        }
    }

    private func readMetadata() -> [StoredAlertEvent] {
        guard let data = try? Data(contentsOf: metadataFileURL) else {
            return []
        }
        return (try? decoder.decode([StoredAlertEvent].self, from: data)) ?? []
    }

    private func saveMetadata(_ metadata: [StoredAlertEvent]) {
        guard let data = try? encoder.encode(metadata) else { return }
        try? data.write(to: metadataFileURL, options: .atomic)
    }

    private func prune(_ metadata: [StoredAlertEvent]) -> [StoredAlertEvent] {
        let cutoff = Date().addingTimeInterval(-Self.retentionSeconds)
        return metadata
            .filter { $0.timestamp >= cutoff }
            .sorted { $0.timestamp > $1.timestamp }
            .prefix(Self.maxEventCount)
            .map { $0 }
    }

    private func cleanupOrphanedThumbnails(validIDs: Set<UUID>) {
        guard let fileURLs = try? fileManager.contentsOfDirectory(
            at: thumbnailsDirectory,
            includingPropertiesForKeys: nil
        ) else {
            return
        }

        let validNames = Set(validIDs.map { "\($0.uuidString).jpg" })
        for url in fileURLs where !validNames.contains(url.lastPathComponent) {
            try? fileManager.removeItem(at: url)
        }
    }
}

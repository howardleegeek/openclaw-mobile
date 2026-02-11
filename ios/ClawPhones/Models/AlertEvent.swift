//
//  AlertEvent.swift
//  ClawPhones
//

import Foundation
import CoreGraphics

struct AlertEvent: Codable, Identifiable {
    let id: UUID
    let type: String
    let confidence: Float
    let timestamp: Date
    let latitude: Double
    let longitude: Double
    let thumbnailData: Data?
    let boundingBox: CGRect?
}

enum AlertTypeFilter: String, CaseIterable, Identifiable {
    case all
    case person
    case vehicle
    case animal

    var id: String { rawValue }

    var title: String {
        switch self {
        case .all:
            return "全部"
        case .person:
            return "Person"
        case .vehicle:
            return "Vehicle"
        case .animal:
            return "Animal"
        }
    }

    func matches(_ event: AlertEvent) -> Bool {
        switch self {
        case .all:
            return true
        default:
            return event.type.lowercased() == rawValue
        }
    }
}

extension AlertEvent {
    var normalizedType: String {
        type.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }

    var displayType: String {
        switch normalizedType {
        case "person":
            return "Person"
        case "vehicle":
            return "Vehicle"
        case "animal":
            return "Animal"
        default:
            return type.isEmpty ? "Unknown" : type
        }
    }

    var typeIconName: String {
        switch normalizedType {
        case "person":
            return "person.fill"
        case "vehicle":
            return "car.fill"
        case "animal":
            return "pawprint.fill"
        default:
            return "exclamationmark.triangle.fill"
        }
    }
}

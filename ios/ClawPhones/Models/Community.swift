//
//  Community.swift
//  ClawPhones
//

import Foundation

struct Community: Codable, Identifiable {
    let id: String
    let name: String
    let description: String
    let centerLat: Double
    let centerLon: Double
    let h3Cells: [String]
    let memberCount: Int
    let inviteCode: String
    let createdAt: Date

    enum CodingKeys: String, CodingKey {
        case id
        case name
        case description
        case centerLat = "center_lat"
        case centerLon = "center_lon"
        case h3Cells = "h3_cells"
        case memberCount = "member_count"
        case inviteCode = "invite_code"
        case createdAt = "created_at"
    }
}

struct CommunityMember: Codable, Identifiable {
    var id: String { userId }
    let userId: String
    let nodeId: String
    let role: CommunityRole
    let joinedAt: Date

    enum CodingKeys: String, CodingKey {
        case userId = "user_id"
        case nodeId = "node_id"
        case role
        case joinedAt = "joined_at"
    }

    // View compatibility
    var name: String {
        return "User \(userId.prefix(8))" // In a real app, this would fetch from user service
    }
}

enum CommunityRole: String, Codable {
    case admin
    case member

    var displayName: String {
        switch self {
        case .admin:
            return "Admin"
        case .member:
            return "Member"
        }
    }

    // View compatibility - alias for 'admin' as 'owner'
    static var owner: CommunityRole {
        return .admin
    }
}

// Alias for backward compatibility
typealias MemberRole = CommunityRole

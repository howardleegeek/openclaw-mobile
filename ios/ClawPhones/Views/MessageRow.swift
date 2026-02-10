//
//  MessageRow.swift
//  ClawPhones
//

import SwiftUI

struct MessageRow: View {
    let message: Message

    private var isUser: Bool {
        message.role == .user
    }

    var body: some View {
        HStack(alignment: .bottom) {
            if isUser {
                Spacer(minLength: 50)
            }

            Text(message.content)
                .font(.body)
                .foregroundStyle(isUser ? Color.white : Color.primary)
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .background(isUser ? Color.accentColor : Color(uiColor: .secondarySystemBackground))
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .frame(maxWidth: .infinity, alignment: isUser ? .trailing : .leading)

            if !isUser {
                Spacer(minLength: 50)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

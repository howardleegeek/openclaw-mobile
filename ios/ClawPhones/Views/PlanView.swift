//
//  PlanView.swift
//  ClawPhones
//

import SwiftUI

struct PlanView: View {
    let plan: SettingsViewModel.PlanInfo

    @State private var showComingSoon: Bool = false

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                usageCard

                planCards

                Button("升级") {
                    showComingSoon = true
                }
                .buttonStyle(.borderedProminent)
                .padding(.top, 8)
            }
            .padding()
        }
        .navigationTitle("计划")
        .alert("即将推出", isPresented: $showComingSoon) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("订阅升级功能正在开发中。")
        }
    }

    private var usageCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("当前计划：\(plan.tier.displayName)")
                .font(.headline)

            Text("今日用量：\(plan.usedToday)/\(plan.dailyLimit) 条消息")
                .font(.subheadline)
                .foregroundStyle(.secondary)

            ProgressView(value: min(Double(plan.usedToday), Double(plan.dailyLimit)), total: Double(plan.dailyLimit))
        }
        .padding()
        .background {
            Color.accentColor.opacity(0.08)
        }
        .overlay(
            RoundedRectangle(cornerRadius: 16)
                .stroke(Color.accentColor.opacity(0.35), lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }

    private var planCards: some View {
        VStack(spacing: 12) {
            PlanCardView(
                isCurrent: plan.tier == .free,
                title: "Free",
                subtitle: "DeepSeek",
                price: "¥0/月",
                features: ["50 条/天", "基础聊天"]
            )

            PlanCardView(
                isCurrent: plan.tier == .pro,
                title: "Pro",
                subtitle: "Kimi K2.5",
                price: "¥69/月",
                features: ["500 条/天", "联网搜索", "长上下文"]
            )

            PlanCardView(
                isCurrent: plan.tier == .max,
                title: "Max",
                subtitle: "Claude Sonnet 4",
                price: "¥199/月",
                features: ["无限", "全部功能"]
            )
        }
    }

    private struct PlanCardView: View {
        let isCurrent: Bool
        let title: String
        let subtitle: String
        let price: String
        let features: [String]

        var body: some View {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .firstTextBaseline) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title)
                            .font(.title3)
                            .fontWeight(.semibold)

                        Text(subtitle)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }

                    Spacer()

                    Text(price)
                        .font(.subheadline)
                        .fontWeight(.semibold)
                }

                VStack(alignment: .leading, spacing: 6) {
                    ForEach(features, id: \.self) { item in
                        Label(item, systemImage: "checkmark.circle.fill")
                            .labelStyle(.titleAndIcon)
                            .symbolRenderingMode(.hierarchical)
                            .font(.subheadline)
                    }
                }

                if isCurrent {
                    HStack {
                        Image(systemName: "checkmark.seal.fill")
                        Text("当前计划")
                    }
                    .font(.footnote)
                    .foregroundStyle(.tint)
                    .padding(.top, 4)
                }
            }
            .padding()
            .background {
                if isCurrent {
                    Color.accentColor.opacity(0.06)
                } else {
                    Color(.secondarySystemBackground)
                }
            }
            .overlay(
                RoundedRectangle(cornerRadius: 16)
                    .stroke(isCurrent ? Color.accentColor.opacity(0.55) : Color.clear, lineWidth: 2)
            )
            .clipShape(RoundedRectangle(cornerRadius: 16))
        }
    }
}

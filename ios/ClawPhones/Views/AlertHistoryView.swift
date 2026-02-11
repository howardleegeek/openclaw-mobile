//
//  AlertHistoryView.swift
//  ClawPhones
//

import SwiftUI
import UIKit

struct AlertHistoryView: View {
    private struct SectionGroup: Identifiable {
        let id: String
        let title: String
        let events: [AlertEvent]
    }

    @State private var events: [AlertEvent] = []
    @State private var searchText: String = ""
    @State private var selectedFilter: AlertTypeFilter = .all
    @State private var isLoading = true

    private static let timeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm"
        return formatter
    }()

    private static let monthDayTimeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "M/d HH:mm"
        return formatter
    }()

    var body: some View {
        VStack(spacing: 8) {
            Picker("类型", selection: $selectedFilter) {
                ForEach(AlertTypeFilter.allCases) { filter in
                    Text(filter.title).tag(filter)
                }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 16)
            .padding(.top, 8)

            if isLoading && events.isEmpty {
                Spacer()
                ProgressView()
                Spacer()
            } else if groupedSections.isEmpty {
                Spacer()
                ContentUnavailableView("暂无告警记录", systemImage: "bell.slash")
                Spacer()
            } else {
                List {
                    ForEach(groupedSections) { section in
                        Section(section.title) {
                            ForEach(section.events) { event in
                                NavigationLink {
                                    AlertDetailView(event: event) {
                                        Task { await loadEvents() }
                                    }
                                } label: {
                                    alertRow(event: event)
                                }
                            }
                        }
                    }
                }
                .listStyle(.insetGrouped)
            }
        }
        .navigationTitle("告警历史")
        .searchable(text: $searchText, prompt: "搜索类型")
        .task {
            await loadEvents()
        }
        .refreshable {
            await loadEvents()
        }
    }

    private var filteredEvents: [AlertEvent] {
        let text = searchText.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()

        return events.filter { event in
            guard selectedFilter.matches(event) else { return false }
            guard !text.isEmpty else { return true }

            let indexable = [
                event.displayType.lowercased(),
                event.normalizedType,
                String(format: "%.6f", event.latitude),
                String(format: "%.6f", event.longitude)
            ].joined(separator: " ")
            return indexable.contains(text)
        }
    }

    private var groupedSections: [SectionGroup] {
        var today: [AlertEvent] = []
        var yesterday: [AlertEvent] = []
        var earlier: [AlertEvent] = []
        let calendar = Calendar.current

        for event in filteredEvents {
            if calendar.isDateInToday(event.timestamp) {
                today.append(event)
            } else if calendar.isDateInYesterday(event.timestamp) {
                yesterday.append(event)
            } else {
                earlier.append(event)
            }
        }

        var sections: [SectionGroup] = []
        if !today.isEmpty {
            sections.append(SectionGroup(id: "today", title: "今天", events: today))
        }
        if !yesterday.isEmpty {
            sections.append(SectionGroup(id: "yesterday", title: "昨天", events: yesterday))
        }
        if !earlier.isEmpty {
            sections.append(SectionGroup(id: "earlier", title: "更早", events: earlier))
        }
        return sections
    }

    private func alertRow(event: AlertEvent) -> some View {
        HStack(spacing: 12) {
            Group {
                if let data = event.thumbnailData, let image = UIImage(data: data) {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                } else {
                    ZStack {
                        Color(uiColor: .secondarySystemBackground)
                        Image(systemName: "photo")
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .frame(width: 72, height: 54)
            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))

            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Image(systemName: event.typeIconName)
                        .foregroundStyle(.orange)
                    Text(event.displayType)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.primary)
                }

                Text(timestampText(for: event.timestamp))
                    .font(.caption)
                    .foregroundStyle(.secondary)

                Text("置信度 \(confidencePercent(event.confidence))%")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Spacer(minLength: 0)
        }
        .padding(.vertical, 2)
    }

    private func timestampText(for date: Date) -> String {
        if Calendar.current.isDateInToday(date) || Calendar.current.isDateInYesterday(date) {
            return Self.timeFormatter.string(from: date)
        }
        return Self.monthDayTimeFormatter.string(from: date)
    }

    private func confidencePercent(_ value: Float) -> Int {
        if value <= 1 {
            return max(0, min(100, Int((value * 100).rounded())))
        }
        return max(0, min(100, Int(value.rounded())))
    }

    @MainActor
    private func loadEvents() async {
        isLoading = true
        let loaded = await AlertEventStore.shared.loadEvents()
        events = loaded
        isLoading = false
    }
}

import SwiftUI
import MapKit
import CoreLocation

struct CoverageMapView: View {
    @StateObject private var viewModel = CoverageMapViewModel()

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("ClawVision 覆盖热力图")
                    .font(.headline)

                Spacer()

                Button {
                    Task { await viewModel.refresh() }
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .buttonStyle(.plain)
            }

            legend

            ZStack {
                CoverageMapRepresentable(
                    cells: viewModel.cells,
                    nodes: viewModel.nodes,
                    userLocation: viewModel.userLocation
                )
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))

                if viewModel.isLoading {
                    ProgressView("加载覆盖数据…")
                        .padding(.horizontal, 14)
                        .padding(.vertical, 10)
                        .background(.ultraThinMaterial, in: Capsule())
                }
            }

            if let error = viewModel.errorMessage {
                Text(error)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .task {
            await viewModel.refresh()
        }
        .onAppear {
            viewModel.startLocationUpdates()
        }
        .onDisappear {
            viewModel.stopLocationUpdates()
        }
    }

    private var legend: some View {
        HStack(spacing: 14) {
            legendItem(color: CoverageStatus.recent.fillColor, label: "最近 1h")
            legendItem(color: CoverageStatus.stale.fillColor, label: "1-24h")
            legendItem(color: CoverageStatus.empty.fillColor, label: "无覆盖")
        }
        .font(.caption)
        .foregroundStyle(.secondary)
    }

    private func legendItem(color: UIColor, label: String) -> some View {
        HStack(spacing: 6) {
            Circle()
                .fill(Color(color))
                .frame(width: 9, height: 9)
            Text(label)
        }
    }
}

private enum CoverageStatus: String {
    case recent
    case stale
    case empty

    var fillColor: UIColor {
        switch self {
        case .recent:
            return UIColor(red: 67.0 / 255.0, green: 160.0 / 255.0, blue: 71.0 / 255.0, alpha: 0.30)
        case .stale:
            return UIColor(red: 251.0 / 255.0, green: 192.0 / 255.0, blue: 45.0 / 255.0, alpha: 0.28)
        case .empty:
            return UIColor(red: 120.0 / 255.0, green: 120.0 / 255.0, blue: 120.0 / 255.0, alpha: 0.25)
        }
    }

    var strokeColor: UIColor {
        switch self {
        case .recent:
            return UIColor(red: 56.0 / 255.0, green: 142.0 / 255.0, blue: 60.0 / 255.0, alpha: 0.95)
        case .stale:
            return UIColor(red: 245.0 / 255.0, green: 181.0 / 255.0, blue: 0.0 / 255.0, alpha: 0.95)
        case .empty:
            return UIColor(red: 99.0 / 255.0, green: 99.0 / 255.0, blue: 99.0 / 255.0, alpha: 0.92)
        }
    }
}

private struct CoverageCell: Identifiable {
    let id: String
    let status: CoverageStatus
    let coordinates: [CLLocationCoordinate2D]
}

private struct CoverageNode: Identifiable {
    let id: String
    let coordinate: CLLocationCoordinate2D
    let isSelf: Bool
    let title: String
}

@MainActor
private final class CoverageMapViewModel: NSObject, ObservableObject {
    @Published var cells: [CoverageCell] = []
    @Published var nodes: [CoverageNode] = []
    @Published var userLocation: CLLocationCoordinate2D?
    @Published var isLoading: Bool = false
    @Published var errorMessage: String?

    private let locationManager = CLLocationManager()
    private var hasRequestedAuthorization = false

    override init() {
        super.init()
        locationManager.delegate = self
        locationManager.desiredAccuracy = kCLLocationAccuracyHundredMeters
        locationManager.distanceFilter = 25
    }

    func refresh() async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }

        do {
            let payload = try await OpenClawAPI.shared.getWorldCells(hours: 24, res: 9)
            let (parsedCells, parsedNodes) = parseCoveragePayload(payload)
            cells = parsedCells
            nodes = mergeSelfNodeIfNeeded(nodes: parsedNodes, userLocation: userLocation)
        } catch {
            errorMessage = "覆盖数据加载失败：\(error.localizedDescription)"
        }
    }

    func startLocationUpdates() {
        let status = locationManager.authorizationStatus
        switch status {
        case .notDetermined:
            if !hasRequestedAuthorization {
                hasRequestedAuthorization = true
                locationManager.requestWhenInUseAuthorization()
            }
        case .authorizedAlways, .authorizedWhenInUse:
            locationManager.startUpdatingLocation()
        default:
            break
        }
    }

    func stopLocationUpdates() {
        locationManager.stopUpdatingLocation()
    }

    private func mergeSelfNodeIfNeeded(nodes: [CoverageNode], userLocation: CLLocationCoordinate2D?) -> [CoverageNode] {
        var result = nodes
        guard let userLocation else { return result }

        let hasSelf = result.contains { node in
            node.isSelf || distanceMeters(from: node.coordinate, to: userLocation) < 12
        }
        if !hasSelf {
            result.insert(
                CoverageNode(
                    id: "self-location",
                    coordinate: userLocation,
                    isSelf: true,
                    title: "我的节点"
                ),
                at: 0
            )
        }
        return result
    }

    private func distanceMeters(from a: CLLocationCoordinate2D, to b: CLLocationCoordinate2D) -> CLLocationDistance {
        CLLocation(latitude: a.latitude, longitude: a.longitude)
            .distance(from: CLLocation(latitude: b.latitude, longitude: b.longitude))
    }

    private func parseCoveragePayload(_ data: Data) -> ([CoverageCell], [CoverageNode]) {
        guard
            let object = try? JSONSerialization.jsonObject(with: data),
            let root = object as? [String: Any] ?? wrapArrayAsDictionary(object as? [Any])
        else {
            return ([], [])
        }

        let cells = parseCells(from: root)
        let nodes = parseNodes(from: root)
        return (cells, nodes)
    }

    private func wrapArrayAsDictionary(_ array: [Any]?) -> [String: Any]? {
        guard let array else { return nil }
        return ["cells": array]
    }

    private func parseCells(from root: [String: Any]) -> [CoverageCell] {
        let candidates = arrayFromObject(root["cells"])
            ?? arrayFromObject(nestedObject(root, path: ["data", "cells"]))
            ?? arrayFromObject(root["items"])
            ?? arrayFromObject(root["data"])
            ?? []

        var parsed: [CoverageCell] = []
        parsed.reserveCapacity(candidates.count)

        for (index, item) in candidates.enumerated() {
            guard let cellObject = item as? [String: Any] else { continue }

            let cellId = firstString(
                in: cellObject,
                keys: ["cell_id", "cellId", "cell", "h3", "h3_index", "id"]
            ) ?? "cell-\(index)"

            let explicitStatusRaw = firstString(
                in: cellObject,
                keys: ["status", "coverage_status", "coverage", "state"]
            )

            let lastSeenValue = firstAny(
                in: cellObject,
                keys: ["last_seen_at", "last_seen", "seen_at", "updated_at", "timestamp", "ts"]
            )
            let lastSeenEpoch = parseEpochSeconds(from: lastSeenValue)

            let status: CoverageStatus
            if let explicitStatusRaw {
                status = mapStatus(raw: explicitStatusRaw, fallbackTimestamp: lastSeenEpoch)
            } else {
                status = mapStatus(raw: nil, fallbackTimestamp: lastSeenEpoch)
            }

            var coordinates = parseCoordinates(from: firstAny(in: cellObject, keys: ["boundary", "polygon", "vertices", "coordinates"]))

            if coordinates.count < 3,
               let center = parseCoordinate(from: firstAny(in: cellObject, keys: ["center", "centroid", "location"]))
                    ?? parseCoordinate(from: cellObject)
            {
                coordinates = approximateHexagon(around: center, radiusMeters: 100)
            }

            guard coordinates.count >= 3 else { continue }

            parsed.append(
                CoverageCell(
                    id: cellId,
                    status: status,
                    coordinates: coordinates
                )
            )
        }

        return parsed
    }

    private func parseNodes(from root: [String: Any]) -> [CoverageNode] {
        let candidates = arrayFromObject(root["nodes"])
            ?? arrayFromObject(root["neighbors"])
            ?? arrayFromObject(root["neighbours"])
            ?? arrayFromObject(root["peers"])
            ?? arrayFromObject(nestedObject(root, path: ["data", "nodes"]))
            ?? []

        var parsed: [CoverageNode] = []
        parsed.reserveCapacity(candidates.count)

        for (index, item) in candidates.enumerated() {
            guard let nodeObject = item as? [String: Any] else { continue }
            guard let coordinate = parseCoordinate(from: firstAny(in: nodeObject, keys: ["location", "position", "center"]))
                    ?? parseCoordinate(from: nodeObject)
            else {
                continue
            }

            let id = firstString(in: nodeObject, keys: ["id", "node_id", "device_id", "peer_id"]) ?? "node-\(index)"
            let isSelf = firstBool(in: nodeObject, keys: ["is_self", "self", "mine", "own"]) ?? false
            let title = firstString(in: nodeObject, keys: ["name", "label", "node_name"]) ?? (isSelf ? "我的节点" : "邻居节点")

            parsed.append(CoverageNode(id: id, coordinate: coordinate, isSelf: isSelf, title: title))
        }

        return parsed
    }

    private func nestedObject(_ root: [String: Any], path: [String]) -> Any? {
        var current: Any? = root
        for key in path {
            guard let dict = current as? [String: Any] else { return nil }
            current = dict[key]
        }
        return current
    }

    private func mapStatus(raw: String?, fallbackTimestamp: TimeInterval?) -> CoverageStatus {
        if let raw = raw?.lowercased() {
            if raw.contains("fresh") || raw.contains("recent") || raw.contains("active") || raw.contains("hot") {
                return .recent
            }
            if raw.contains("stale") || raw.contains("old") || raw.contains("warm") || raw.contains("aging") {
                return .stale
            }
            if raw.contains("empty") || raw.contains("none") || raw.contains("missing") || raw.contains("cold") {
                return .empty
            }
        }

        guard let fallbackTimestamp, fallbackTimestamp > 0 else {
            return .empty
        }

        let ageHours = (Date().timeIntervalSince1970 - fallbackTimestamp) / 3600.0
        if ageHours <= 1.0 {
            return .recent
        }
        if ageHours <= 24.0 {
            return .stale
        }
        return .empty
    }

    private func parseEpochSeconds(from value: Any?) -> TimeInterval? {
        if let number = value as? NSNumber {
            let raw = number.doubleValue
            return raw > 2_000_000_000 ? raw / 1000.0 : raw
        }

        if let text = value as? String {
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            if let numeric = Double(trimmed) {
                return numeric > 2_000_000_000 ? numeric / 1000.0 : numeric
            }

            let iso = ISO8601DateFormatter()
            if let date = iso.date(from: trimmed) {
                return date.timeIntervalSince1970
            }
        }

        return nil
    }

    private func parseCoordinates(from source: Any?) -> [CLLocationCoordinate2D] {
        guard let source else { return [] }

        if let entries = source as? [[Any]] {
            let normalized = entries.compactMap(parsePair)
            if normalized.count >= 3 {
                return stripClosedLoop(normalized)
            }
        }

        if let rings = source as? [[[Any]]], let firstRing = rings.first {
            let normalized = firstRing.compactMap(parsePair)
            if normalized.count >= 3 {
                return stripClosedLoop(normalized)
            }
        }

        if let dictArray = source as? [[String: Any]] {
            let normalized = dictArray.compactMap(parseCoordinate(from:))
            if normalized.count >= 3 {
                return stripClosedLoop(normalized)
            }
        }

        if let dict = source as? [String: Any] {
            if let nested = dict["coordinates"] {
                return parseCoordinates(from: nested)
            }
            return []
        }

        return []
    }

    private func stripClosedLoop(_ coords: [CLLocationCoordinate2D]) -> [CLLocationCoordinate2D] {
        guard coords.count > 3 else { return coords }
        guard let first = coords.first, let last = coords.last else { return coords }
        if abs(first.latitude - last.latitude) < 0.000001,
           abs(first.longitude - last.longitude) < 0.000001 {
            return Array(coords.dropLast())
        }
        return coords
    }

    private func parsePair(_ raw: [Any]) -> CLLocationCoordinate2D? {
        guard raw.count >= 2 else { return nil }
        guard let a = parseDouble(raw[0]), let b = parseDouble(raw[1]) else { return nil }
        return makeCoordinate(valueA: a, valueB: b)
    }

    private func parseCoordinate(from source: Any?) -> CLLocationCoordinate2D? {
        guard let source else { return nil }

        if let dict = source as? [String: Any] {
            if let lat = parseDouble(dict["lat"] ?? dict["latitude"]),
               let lng = parseDouble(dict["lng"] ?? dict["lon"] ?? dict["longitude"]) {
                return CLLocationCoordinate2D(latitude: lat, longitude: lng)
            }

            if let arr = dict["coordinates"] as? [Any], arr.count >= 2,
               let a = parseDouble(arr[0]), let b = parseDouble(arr[1]) {
                return makeCoordinate(valueA: a, valueB: b)
            }
        }

        if let arr = source as? [Any], arr.count >= 2,
           let a = parseDouble(arr[0]), let b = parseDouble(arr[1]) {
            return makeCoordinate(valueA: a, valueB: b)
        }

        return nil
    }

    private func makeCoordinate(valueA: Double, valueB: Double) -> CLLocationCoordinate2D {
        if abs(valueA) <= 90.0 && abs(valueB) <= 180.0 {
            return CLLocationCoordinate2D(latitude: valueA, longitude: valueB)
        }
        return CLLocationCoordinate2D(latitude: valueB, longitude: valueA)
    }

    private func approximateHexagon(around center: CLLocationCoordinate2D, radiusMeters: Double) -> [CLLocationCoordinate2D] {
        let earthRadius = 6_378_137.0
        let angularDistance = radiusMeters / earthRadius
        let latRad = center.latitude * .pi / 180.0
        let lonRad = center.longitude * .pi / 180.0

        return (0..<6).map { idx in
            let bearing = Double(idx) * (60.0 * .pi / 180.0)
            let sinLat = sin(latRad) * cos(angularDistance)
                + cos(latRad) * sin(angularDistance) * cos(bearing)
            let newLat = asin(sinLat)
            let y = sin(bearing) * sin(angularDistance) * cos(latRad)
            let x = cos(angularDistance) - sin(latRad) * sin(newLat)
            let newLon = lonRad + atan2(y, x)

            return CLLocationCoordinate2D(
                latitude: newLat * 180.0 / .pi,
                longitude: newLon * 180.0 / .pi
            )
        }
    }

    private func firstString(in dict: [String: Any], keys: [String]) -> String? {
        for key in keys {
            if let value = dict[key] as? String {
                let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
                if !trimmed.isEmpty {
                    return trimmed
                }
            }
        }
        return nil
    }

    private func firstAny(in dict: [String: Any], keys: [String]) -> Any? {
        for key in keys {
            if let value = dict[key] {
                return value
            }
        }
        return nil
    }

    private func firstBool(in dict: [String: Any], keys: [String]) -> Bool? {
        for key in keys {
            if let boolValue = dict[key] as? Bool {
                return boolValue
            }
            if let textValue = dict[key] as? String {
                let lowered = textValue.lowercased()
                if lowered == "true" || lowered == "1" || lowered == "yes" {
                    return true
                }
                if lowered == "false" || lowered == "0" || lowered == "no" {
                    return false
                }
            }
        }
        return nil
    }

    private func arrayFromObject(_ value: Any?) -> [Any]? {
        if let array = value as? [Any] {
            return array
        }
        return nil
    }

    private func parseDouble(_ value: Any?) -> Double? {
        if let number = value as? NSNumber {
            return number.doubleValue
        }
        if let text = value as? String {
            return Double(text.trimmingCharacters(in: .whitespacesAndNewlines))
        }
        return nil
    }
}

@MainActor
extension CoverageMapViewModel: @preconcurrency CLLocationManagerDelegate {
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        startLocationUpdates()
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let latest = locations.last else { return }
        userLocation = latest.coordinate
        nodes = mergeSelfNodeIfNeeded(nodes: nodes, userLocation: latest.coordinate)
    }
}

private struct CoverageMapRepresentable: UIViewRepresentable {
    let cells: [CoverageCell]
    let nodes: [CoverageNode]
    let userLocation: CLLocationCoordinate2D?

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    func makeUIView(context: Context) -> MKMapView {
        let mapView = MKMapView(frame: .zero)
        mapView.delegate = context.coordinator
        mapView.isRotateEnabled = true
        mapView.isPitchEnabled = false
        mapView.showsCompass = true
        mapView.showsScale = true
        mapView.showsUserLocation = true
        mapView.pointOfInterestFilter = .excludingAll
        mapView.mapType = .mutedStandard
        return mapView
    }

    func updateUIView(_ uiView: MKMapView, context: Context) {
        context.coordinator.render(
            mapView: uiView,
            cells: cells,
            nodes: nodes,
            userLocation: userLocation
        )
    }

    final class Coordinator: NSObject, MKMapViewDelegate {
        private var overlayStatusById: [ObjectIdentifier: CoverageStatus] = [:]
        private var hasSetInitialRegion = false

        func render(
            mapView: MKMapView,
            cells: [CoverageCell],
            nodes: [CoverageNode],
            userLocation: CLLocationCoordinate2D?
        ) {
            mapView.removeOverlays(mapView.overlays)
            overlayStatusById.removeAll(keepingCapacity: true)

            for cell in cells where cell.coordinates.count >= 3 {
                var coords = cell.coordinates
                let polygon = MKPolygon(coordinates: &coords, count: coords.count)
                overlayStatusById[ObjectIdentifier(polygon)] = cell.status
                mapView.addOverlay(polygon)
            }

            let removableAnnotations = mapView.annotations.filter { !($0 is MKUserLocation) }
            mapView.removeAnnotations(removableAnnotations)

            var annotations: [MKPointAnnotation] = []
            for node in nodes {
                let annotation = MKPointAnnotation()
                annotation.coordinate = node.coordinate
                annotation.title = node.title
                annotation.subtitle = node.isSelf ? "Self" : "Neighbor"
                annotations.append(annotation)
            }
            mapView.addAnnotations(annotations)

            if !hasSetInitialRegion {
                if let userLocation {
                    let region = MKCoordinateRegion(
                        center: userLocation,
                        latitudinalMeters: 1_200,
                        longitudinalMeters: 1_200
                    )
                    mapView.setRegion(region, animated: false)
                    hasSetInitialRegion = true
                    return
                }

                if !cells.isEmpty {
                    var rect = MKMapRect.null
                    for overlay in mapView.overlays {
                        rect = rect.union(overlay.boundingMapRect)
                    }
                    if !rect.isNull {
                        mapView.setVisibleMapRect(
                            rect,
                            edgePadding: UIEdgeInsets(top: 40, left: 28, bottom: 40, right: 28),
                            animated: false
                        )
                        hasSetInitialRegion = true
                    }
                }
            }
        }

        func mapView(_ mapView: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
            guard let polygon = overlay as? MKPolygon else {
                return MKOverlayRenderer(overlay: overlay)
            }

            let status = overlayStatusById[ObjectIdentifier(overlay)] ?? .empty
            let renderer = MKPolygonRenderer(polygon: polygon)
            renderer.fillColor = status.fillColor
            renderer.strokeColor = status.strokeColor
            renderer.lineWidth = 1.5
            return renderer
        }

        func mapView(_ mapView: MKMapView, viewFor annotation: MKAnnotation) -> MKAnnotationView? {
            if annotation is MKUserLocation {
                return nil
            }

            let reuseIdentifier = "coverage-node"
            let marker = (mapView.dequeueReusableAnnotationView(withIdentifier: reuseIdentifier) as? MKMarkerAnnotationView)
                ?? MKMarkerAnnotationView(annotation: annotation, reuseIdentifier: reuseIdentifier)

            marker.annotation = annotation
            marker.canShowCallout = true

            if annotation.subtitle == "Self" {
                marker.markerTintColor = UIColor.systemBlue
                marker.glyphImage = UIImage(systemName: "person.crop.circle.fill")
            } else {
                marker.markerTintColor = UIColor.systemOrange
                marker.glyphImage = UIImage(systemName: "dot.radiowaves.left.and.right")
            }

            return marker
        }
    }
}

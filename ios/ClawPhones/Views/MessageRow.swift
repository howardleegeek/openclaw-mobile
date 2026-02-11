//
//  MessageRow.swift
//  ClawPhones
//

import SwiftUI
import UIKit
import ImageIO

struct MessageRow: View {
    let message: Message
    let timestampText: String?
    let onRetry: (() -> Void)?
    let onCopy: (() -> Void)?
    let onRegenerate: (() -> Void)?
    let onDelete: () -> Void

    private var isUser: Bool {
        message.role == .user
    }

    private var isAssistant: Bool {
        message.role == .assistant
    }

    private enum ContentSegment {
        case markdown(String)
        case code(language: String?, code: String)
    }

    private static let swiftKeywords: Set<String> = [
        "as", "associatedtype", "async", "await", "break", "case", "catch", "class",
        "continue", "default", "defer", "do", "else", "enum", "extension", "false",
        "for", "func", "guard", "if", "import", "in", "init", "inout", "is", "let",
        "nil", "operator", "private", "protocol", "public", "repeat", "return", "self",
        "static", "struct", "subscript", "switch", "throw", "throws", "true", "try",
        "typealias", "var", "where", "while"
    ]

    private static let pythonKeywords: Set<String> = [
        "and", "as", "assert", "async", "await", "break", "class", "continue", "def",
        "del", "elif", "else", "except", "False", "finally", "for", "from", "global",
        "if", "import", "in", "is", "lambda", "None", "nonlocal", "not", "or", "pass",
        "raise", "return", "True", "try", "while", "with", "yield"
    ]

    private static let javascriptKeywords: Set<String> = [
        "await", "break", "case", "catch", "class", "const", "continue", "debugger",
        "default", "delete", "do", "else", "export", "extends", "false", "finally",
        "for", "function", "if", "import", "in", "instanceof", "let", "new", "null",
        "return", "super", "switch", "this", "throw", "true", "try", "typeof", "var",
        "while", "with", "yield"
    ]

    private static let fileCardOpen = "[[FILE_CARD]]"
    private static let fileCardClose = "[[/FILE_CARD]]"
    private static let fileContextOpen = "[[FILE_CONTEXT]]"
    private static let fileContextClose = "[[/FILE_CONTEXT]]"

    private struct FileCardPayload {
        let name: String
        let size: Int
        let type: String
        let extraText: String
    }

    var body: some View {
        HStack(alignment: .bottom) {
            if isUser {
                Spacer(minLength: 50)
            }

            VStack(alignment: .trailing, spacing: 0) {
                contentView
                    .font(.body)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                    .background(isUser ? Color.accentColor : Color(uiColor: .secondarySystemBackground))
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .contextMenu {
                        Button("复制") {
                            UIPasteboard.general.string = message.content
                            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                            onCopy?()
                        }

                        if isAssistant, let onRegenerate {
                            Button("重新生成") {
                                onRegenerate()
                            }
                        }

                        Button("删除", role: .destructive) {
                            onDelete()
                        }
                    }

                if let timestampText {
                    Text(timestampText)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .padding(.top, 2)
                }

                if isUser, message.deliveryState != .sent {
                    HStack(spacing: 8) {
                        Text(message.deliveryState == .failed ? "发送失败" : "发送中...")
                            .font(.caption2)
                            .foregroundStyle(message.deliveryState == .failed ? Color.red.opacity(0.9) : .secondary)

                        if message.deliveryState == .failed, let onRetry {
                            Button("重试", action: onRetry)
                                .font(.caption2.weight(.semibold))
                                .buttonStyle(.plain)
                                .foregroundStyle(Color.accentColor)
                        }
                    }
                    .padding(.top, 4)
                }
            }
            .frame(maxWidth: .infinity, alignment: isUser ? .trailing : .leading)

            if !isUser {
                Spacer(minLength: 50)
            }
        }
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private var contentView: some View {
        let visual = parseVisualPayload(from: message.content)
        if isUser {
            if let payload = parseFileCardPayload(from: message.content) {
                userFileCardView(payload)
            } else {
                messageTextWithOptionalImage(
                    text: visual.text,
                    imageURL: visual.imageURL,
                    foregroundColor: message.deliveryState == .sending ? Color(white: 0.78) : Color.white
                )
            }
        } else {
            assistantContentView(text: visual.text, imageURL: visual.imageURL)
        }
    }

    private func userFileCardView(_ payload: FileCardPayload) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 10) {
                Text(fileIcon(for: payload.type))
                    .font(.system(size: 24))
                VStack(alignment: .leading, spacing: 2) {
                    Text(payload.name)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(message.deliveryState == .sending ? Color(white: 0.82) : Color.white)
                        .lineLimit(2)
                    Text(formatFileSize(payload.size))
                        .font(.caption)
                        .foregroundStyle(Color.white.opacity(0.82))
                }
            }
            if !payload.extraText.isEmpty {
                Text(payload.extraText)
                    .font(.footnote)
                    .foregroundStyle(Color.white.opacity(0.9))
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private func assistantContentView(text: String, imageURL: String?) -> some View {
        if text.isEmpty, imageURL == nil {
            VStack(alignment: .leading, spacing: 4) {
                ThinkingIndicator()
                Text("正在思考...")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding(.vertical, 2)
        } else {
            let segments = text.isEmpty ? [] : parseContentSegments(from: text)

            VStack(alignment: .leading, spacing: 8) {
                if let imageURL {
                    CachedThumbnailView(urlString: imageURL, maxWidth: 220)
                }
                ForEach(Array(segments.enumerated()), id: \.offset) { _, segment in
                    segmentView(segment)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    @ViewBuilder
    private func messageTextWithOptionalImage(text: String, imageURL: String?, foregroundColor: Color) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            if let imageURL {
                CachedThumbnailView(urlString: imageURL, maxWidth: 220)
            }
            if !text.isEmpty {
                Text(text)
                    .foregroundStyle(foregroundColor)
            }
        }
    }

    @ViewBuilder
    private func segmentView(_ segment: ContentSegment) -> some View {
        switch segment {
        case let .markdown(text):
            markdownTextView(text)
        case let .code(language, code):
            codeBlockView(language: language, code: code)
        }
    }

    @ViewBuilder
    private func markdownTextView(_ markdown: String) -> some View {
        if let attributed = try? AttributedString(
            markdown: markdown,
            options: .init(interpretedSyntax: .full)
        ) {
            Text(attributed)
                .foregroundStyle(Color.primary)
                .tint(Color.accentColor)
        } else {
            Text(markdown)
                .foregroundStyle(Color.primary)
        }
    }

    private func codeBlockView(language: String?, code: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            if let tag = normalizedLanguageTag(language) {
                Text(tag.uppercased())
                    .font(.caption2)
                    .foregroundStyle(Color.white.opacity(0.7))
            }

            ScrollView(.horizontal, showsIndicators: true) {
                highlightedCodeText(code: code, language: language)
                    .font(.system(.body, design: .monospaced))
                    .textSelection(.enabled)
                    .fixedSize(horizontal: true, vertical: true)
            }
        }
        .padding(10)
        .background(Color(red: 0.08, green: 0.09, blue: 0.12))
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
    }

    private func parseContentSegments(from markdown: String) -> [ContentSegment] {
        let pattern = "```([A-Za-z0-9_+-]*)[ \\t]*\\n([\\s\\S]*?)```"
        guard let regex = try? NSRegularExpression(pattern: pattern) else {
            return [.markdown(markdown)]
        }

        let range = NSRange(markdown.startIndex..<markdown.endIndex, in: markdown)
        let matches = regex.matches(in: markdown, options: [], range: range)
        guard !matches.isEmpty else {
            return [.markdown(markdown)]
        }

        var segments: [ContentSegment] = []
        var cursor = markdown.startIndex

        for match in matches {
            guard let fullRange = Range(match.range, in: markdown) else { continue }

            if cursor < fullRange.lowerBound {
                segments.append(.markdown(String(markdown[cursor..<fullRange.lowerBound])))
            }

            let language: String? = {
                guard let languageRange = Range(match.range(at: 1), in: markdown) else { return nil }
                let value = String(markdown[languageRange]).trimmingCharacters(in: .whitespacesAndNewlines)
                return value.isEmpty ? nil : value.lowercased()
            }()

            var code = ""
            if let codeRange = Range(match.range(at: 2), in: markdown) {
                code = String(markdown[codeRange]).replacingOccurrences(of: "\r\n", with: "\n")
            }

            if code.hasSuffix("\n") {
                code.removeLast()
            }

            segments.append(.code(language: language, code: code))
            cursor = fullRange.upperBound
        }

        if cursor < markdown.endIndex {
            segments.append(.markdown(String(markdown[cursor..<markdown.endIndex])))
        }

        return segments
    }

    private func highlightedCodeText(code: String, language: String?) -> Text {
        let normalizedCode = code.replacingOccurrences(of: "\r\n", with: "\n")
        let lines = normalizedCode.split(separator: "\n", omittingEmptySubsequences: false)
        let keywordSet = keywordSet(for: language)

        var output = Text("")
        for (index, line) in lines.enumerated() {
            output = output + highlightedLine(String(line), language: language, keywords: keywordSet)
            if index < lines.count - 1 {
                output = output + Text("\n")
            }
        }

        return output
    }

    private func highlightedLine(_ line: String, language: String?, keywords: Set<String>) -> Text {
        let (codePart, commentPart) = splitComment(from: line, language: language)
        var output = tokenizeCode(codePart, keywords: keywords)

        if let commentPart, !commentPart.isEmpty {
            output = output + Text(commentPart).foregroundColor(Color(red: 0.45, green: 0.74, blue: 0.5))
        }

        return output
    }

    private func splitComment(from line: String, language: String?) -> (String, String?) {
        let normalized = normalizedLanguage(language)
        guard normalized == "python" || normalized == "swift" || normalized == "javascript" else {
            return (line, nil)
        }

        var index = line.startIndex
        var inSingleQuote = false
        var inDoubleQuote = false
        var isEscaped = false

        while index < line.endIndex {
            let character = line[index]

            if isEscaped {
                isEscaped = false
            } else if character == "\\" {
                isEscaped = inSingleQuote || inDoubleQuote
            } else if character == "\"" && !inSingleQuote {
                inDoubleQuote.toggle()
            } else if character == "'" && !inDoubleQuote {
                inSingleQuote.toggle()
            } else if !inSingleQuote && !inDoubleQuote {
                if normalized == "python", character == "#" {
                    return (String(line[..<index]), String(line[index...]))
                }

                if normalized == "swift" || normalized == "javascript" {
                    let nextIndex = line.index(after: index)
                    if character == "/", nextIndex < line.endIndex, line[nextIndex] == "/" {
                        return (String(line[..<index]), String(line[index...]))
                    }
                }
            }

            index = line.index(after: index)
        }

        return (line, nil)
    }

    private func tokenizeCode(_ text: String, keywords: Set<String>) -> Text {
        let pattern = "\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|\\b[A-Za-z_][A-Za-z0-9_]*\\b"
        guard let regex = try? NSRegularExpression(pattern: pattern) else {
            return Text(text).foregroundColor(.white)
        }

        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        let matches = regex.matches(in: text, options: [], range: range)
        var cursor = text.startIndex
        var output = Text("")

        for match in matches {
            guard let tokenRange = Range(match.range, in: text) else { continue }

            if cursor < tokenRange.lowerBound {
                let plain = String(text[cursor..<tokenRange.lowerBound])
                output = output + Text(plain).foregroundColor(Color.white.opacity(0.92))
            }

            let token = String(text[tokenRange])
            if token.hasPrefix("\"") || token.hasPrefix("'") {
                output = output + Text(token).foregroundColor(Color(red: 0.96, green: 0.69, blue: 0.32))
            } else if keywords.contains(token) {
                output = output + Text(token).foregroundColor(Color(red: 0.45, green: 0.78, blue: 1.0))
            } else {
                output = output + Text(token).foregroundColor(Color.white.opacity(0.92))
            }

            cursor = tokenRange.upperBound
        }

        if cursor < text.endIndex {
            output = output + Text(String(text[cursor...])).foregroundColor(Color.white.opacity(0.92))
        }

        return output
    }

    private struct VisualPayload {
        let text: String
        let imageURL: String?
    }

    private func parseVisualPayload(from raw: String) -> VisualPayload {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            return VisualPayload(text: "", imageURL: nil)
        }

        var extractedText = trimmed
        var imageURL: String?

        if let data = trimmed.data(using: .utf8),
           let object = try? JSONSerialization.jsonObject(with: data, options: []) {
            let parsed = extractVisualPayload(from: object)
            if !parsed.text.isEmpty {
                extractedText = parsed.text
            }
            if let parsedImage = parsed.imageURL {
                imageURL = parsedImage
            }
        }

        if imageURL == nil {
            imageURL = firstMarkdownImageURL(in: extractedText)
        }
        if let imageURL {
            extractedText = stripMarkdownImages(from: extractedText).trimmingCharacters(in: .whitespacesAndNewlines)
            if extractedText.isEmpty, trimmed.hasPrefix("{"), trimmed.hasSuffix("}") {
                extractedText = ""
            }
            return VisualPayload(text: extractedText, imageURL: imageURL)
        }

        return VisualPayload(text: extractedText, imageURL: nil)
    }

    private func extractVisualPayload(from object: Any) -> VisualPayload {
        switch object {
        case let dict as [String: Any]:
            var textParts: [String] = []
            var imageURL: String?

            for key in ["text", "content", "message", "caption"] {
                if let value = dict[key] as? String {
                    let normalized = value.trimmingCharacters(in: .whitespacesAndNewlines)
                    if !normalized.isEmpty, !normalized.lowercased().hasPrefix("http") {
                        textParts.append(normalized)
                    }
                }
            }

            for key in ["image_url", "imageUrl", "url"] {
                if let value = dict[key] as? String {
                    let candidate = value.trimmingCharacters(in: .whitespacesAndNewlines)
                    if candidate.lowercased().hasPrefix("http") {
                        imageURL = imageURL ?? candidate
                    }
                }
            }

            for key in ["content", "parts", "image"] {
                if let nested = dict[key] {
                    let nestedPayload = extractVisualPayload(from: nested)
                    if !nestedPayload.text.isEmpty {
                        textParts.append(nestedPayload.text)
                    }
                    if let nestedImage = nestedPayload.imageURL {
                        imageURL = imageURL ?? nestedImage
                    }
                }
            }

            let mergedText = textParts
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                .filter { !$0.isEmpty }
                .joined(separator: "\n")
            return VisualPayload(text: mergedText, imageURL: imageURL)

        case let array as [Any]:
            var textParts: [String] = []
            var imageURL: String?
            for item in array {
                let payload = extractVisualPayload(from: item)
                if !payload.text.isEmpty {
                    textParts.append(payload.text)
                }
                if let nestedImage = payload.imageURL {
                    imageURL = imageURL ?? nestedImage
                }
            }
            return VisualPayload(text: textParts.joined(separator: "\n"), imageURL: imageURL)

        case let value as String:
            let normalized = value.trimmingCharacters(in: .whitespacesAndNewlines)
            if normalized.lowercased().hasPrefix("http") {
                return VisualPayload(text: "", imageURL: normalized)
            }
            return VisualPayload(text: normalized, imageURL: nil)

        default:
            return VisualPayload(text: "", imageURL: nil)
        }
    }

    private func firstMarkdownImageURL(in text: String) -> String? {
        let pattern = "!\\[[^\\]]*\\]\\((https?://[^\\s)]+)\\)"
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return nil }
        let nsRange = NSRange(text.startIndex..<text.endIndex, in: text)
        guard let match = regex.firstMatch(in: text, options: [], range: nsRange),
              let range = Range(match.range(at: 1), in: text) else {
            return nil
        }
        return String(text[range])
    }

    private func stripMarkdownImages(from text: String) -> String {
        let pattern = "!\\[[^\\]]*\\]\\((https?://[^\\s)]+)\\)"
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return text }
        let nsRange = NSRange(text.startIndex..<text.endIndex, in: text)
        return regex.stringByReplacingMatches(in: text, options: [], range: nsRange, withTemplate: "")
    }

    private func parseFileCardPayload(from raw: String) -> FileCardPayload? {
        guard let cardStart = raw.range(of: Self.fileCardOpen),
              let cardEnd = raw.range(of: Self.fileCardClose),
              cardStart.upperBound <= cardEnd.lowerBound else {
            return nil
        }

        let jsonString = String(raw[cardStart.upperBound..<cardEnd.lowerBound]).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !jsonString.isEmpty,
              let jsonData = jsonString.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: jsonData) as? [String: Any] else {
            return nil
        }

        let rawName = ((object["name"] as? String) ?? "file").trimmingCharacters(in: .whitespacesAndNewlines)
        let name = rawName.isEmpty ? "file" : rawName
        let size = (object["size"] as? NSNumber)?.intValue ?? 0
        let type = ((object["type"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)

        var tail = String(raw[cardEnd.upperBound...])
        if let contextStart = tail.range(of: Self.fileContextOpen),
           let contextEnd = tail.range(of: Self.fileContextClose),
           contextStart.upperBound <= contextEnd.lowerBound {
            let before = tail[..<contextStart.lowerBound]
            let after = tail[contextEnd.upperBound...]
            tail = String(before + after)
        }
        let extraText = tail.trimmingCharacters(in: .whitespacesAndNewlines)

        return FileCardPayload(name: name, size: max(0, size), type: type, extraText: extraText)
    }

    private func fileIcon(for type: String) -> String {
        switch type.lowercased() {
        case "pdf":
            return "📕"
        case "csv":
            return "📊"
        case "json":
            return "🧩"
        case "md":
            return "📝"
        case "txt":
            return "📄"
        default:
            return "📎"
        }
    }

    private func formatFileSize(_ bytes: Int) -> String {
        let value = Double(max(0, bytes))
        if value < 1024 {
            return "\(Int(value)) B"
        }
        if value < 1024 * 1024 {
            return String(format: "%.1f KB", value / 1024)
        }
        return String(format: "%.2f MB", value / (1024 * 1024))
    }

    private func normalizedLanguage(_ language: String?) -> String {
        guard let language else { return "plain" }
        switch language.lowercased() {
        case "swift":
            return "swift"
        case "py", "python":
            return "python"
        case "js", "javascript", "ts", "typescript", "tsx":
            return "javascript"
        default:
            return "plain"
        }
    }

    private func normalizedLanguageTag(_ language: String?) -> String? {
        let normalized = normalizedLanguage(language)
        return normalized == "plain" ? nil : normalized
    }

    private func keywordSet(for language: String?) -> Set<String> {
        switch normalizedLanguage(language) {
        case "swift":
            return Self.swiftKeywords
        case "python":
            return Self.pythonKeywords
        case "javascript":
            return Self.javascriptKeywords
        default:
            return Self.swiftKeywords.union(Self.pythonKeywords).union(Self.javascriptKeywords)
        }
    }
}

private struct CachedThumbnailView: View {
    let urlString: String
    let maxWidth: CGFloat

    @State private var image: UIImage?
    @State private var isLoading = false

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(Color.black.opacity(0.06))
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else if isLoading {
                ProgressView()
                    .progressViewStyle(.circular)
            } else {
                Image(systemName: "photo")
                    .font(.system(size: 22, weight: .medium))
                    .foregroundStyle(.secondary)
            }
        }
        .frame(width: maxWidth, height: maxWidth * 0.72)
        .clipped()
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        .task(id: urlString) {
            await loadImage()
        }
    }

    @MainActor
    private func loadImage() async {
        guard let url = URL(string: urlString) else { return }
        isLoading = true
        defer { isLoading = false }
        image = await MessageThumbnailCache.shared.image(for: url, maxPixelSize: 512)
    }
}

final class MessageThumbnailCache {
    static let shared = MessageThumbnailCache()

    private let cache = NSCache<NSString, UIImage>()

    private init() {
        cache.countLimit = 240
        cache.totalCostLimit = 40 * 1024 * 1024
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(onMemoryWarning),
            name: UIApplication.didReceiveMemoryWarningNotification,
            object: nil
        )
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    func clear() {
        cache.removeAllObjects()
    }

    @objc
    private func onMemoryWarning() {
        clear()
    }

    func image(for url: URL, maxPixelSize: CGFloat) async -> UIImage? {
        let key = cacheKey(url: url, maxPixelSize: maxPixelSize) as NSString
        if let cached = cache.object(forKey: key) {
            return cached
        }

        do {
            let (data, response) = try await URLSession.shared.data(from: url)
            guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
                return nil
            }

            guard let image = downsample(data: data, maxPixelSize: maxPixelSize) else {
                return nil
            }

            cache.setObject(image, forKey: key, cost: imageCost(image))
            return image
        } catch {
            return nil
        }
    }

    private func cacheKey(url: URL, maxPixelSize: CGFloat) -> String {
        "\(url.absoluteString)#\(Int(max(1, maxPixelSize)))"
    }

    private func imageCost(_ image: UIImage) -> Int {
        let width = Int(image.size.width * image.scale)
        let height = Int(image.size.height * image.scale)
        return max(1, width * height * 4)
    }

    private func downsample(data: Data, maxPixelSize: CGFloat) -> UIImage? {
        let sourceOptions: [CFString: Any] = [
            kCGImageSourceShouldCache: false
        ]
        guard let source = CGImageSourceCreateWithData(data as CFData, sourceOptions as CFDictionary) else {
            return nil
        }

        let downsampleOptions: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: Int(max(1, maxPixelSize))
        ]

        guard let cgImage = CGImageSourceCreateThumbnailAtIndex(
            source,
            0,
            downsampleOptions as CFDictionary
        ) else {
            return nil
        }

        return UIImage(cgImage: cgImage)
    }
}

import Foundation

/// One progress line from the engine (`eco --progress-json`): "@eco " followed by JSON.
public struct EcoEvent: Decodable, Equatable {
    public var event: String
    public var text: String?
    public var message: String?
    public var language: String?
    public var languages: [String]?
    public var done: Int?
    public var total: Int?
    public var work: String?
    public var files: [String]?
    public var engine: String?
    public var device: String?
    public var lines: Int?

    public static let prefix = "@eco "

    /// The event on this output line, or nil for an ordinary log line.
    public static func parse(_ line: String) -> EcoEvent? {
        guard line.hasPrefix(prefix) else { return nil }
        let json = Data(line.dropFirst(prefix.count).utf8)
        return try? JSONDecoder().decode(EcoEvent.self, from: json)
    }
}

/// Splits a byte stream into lines, keeping the unfinished tail for the next chunk.
public struct LineBuffer {
    private var pending = Data()

    public init() {}

    public mutating func append(_ data: Data) -> [String] {
        pending.append(data)
        var lines: [String] = []
        while let newline = pending.firstIndex(of: UInt8(ascii: "\n")) {
            let line = pending[pending.startIndex..<newline]
            lines.append(String(decoding: line, as: UTF8.self).trimmingCharacters(in: .init(charactersIn: "\r")))
            pending = Data(pending[pending.index(after: newline)...])
        }
        return lines
    }

    public mutating func flush() -> [String] {
        guard !pending.isEmpty else { return [] }
        defer { pending = Data() }
        return [String(decoding: pending, as: UTF8.self)]
    }
}

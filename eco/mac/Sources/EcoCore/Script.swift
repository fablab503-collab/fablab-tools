import Foundation

/// A line of the transcript, as in <video>.eco/transcript.json.
public struct TranscriptLine: Codable, Identifiable, Equatable {
    public var id: Int
    public var start: Double
    public var end: Double
    public var text: String
}

public struct Transcript: Codable, Equatable {
    public var language: String
    public var lines: [TranscriptLine]
}

/// A translated line, as in <video>.eco/<language>.json. The engine re-voices a line when its
/// text or take changes, and only rephrases lines whose text still equals `machine`, so an
/// edit made here is never overwritten.
public struct DubLine: Codable, Identifiable, Equatable {
    public var id: Int
    public var start: Double
    public var end: Double
    public var source: String
    public var text: String
    public var machine: String?
    public var take: Int?

    public var editedByHand: Bool { machine != nil && text != machine }
}

public struct LanguageScript: Codable, Equatable {
    public var language: String
    public var lines: [DubLine]
}

/// Reading and writing the engine's working folder for one video.
public struct WorkFolder {
    public let url: URL

    public init(url: URL) { self.url = url }

    /// The folder the engine uses for this video: "talk.mp4" works in "talk.eco".
    public init(video: URL) {
        url = video.deletingPathExtension().appendingPathExtension("eco")
    }

    public var transcriptURL: URL { url.appendingPathComponent("transcript.json") }
    public func scriptURL(_ language: String) -> URL { url.appendingPathComponent("\(language).json") }

    public func transcript() throws -> Transcript {
        try JSONDecoder().decode(Transcript.self, from: Data(contentsOf: transcriptURL))
    }

    public func script(_ language: String) throws -> LanguageScript {
        try JSONDecoder().decode(LanguageScript.self, from: Data(contentsOf: scriptURL(language)))
    }

    public func save(_ transcript: Transcript) throws {
        try write(transcript, to: transcriptURL)
    }

    public func save(_ script: LanguageScript) throws {
        try write(script, to: scriptURL(script.language))
    }

    /// Languages that already have a script in this folder.
    public func languages() -> [String] {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: url.path)) ?? []
        return names.compactMap { name in
            guard name.hasSuffix(".json"), name != "transcript.json" else { return nil }
            return String(name.dropLast(5))
        }.sorted()
    }

    private func write<T: Encodable>(_ value: T, to file: URL) throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .withoutEscapingSlashes]
        try encoder.encode(value).write(to: file, options: .atomic)
    }
}

/// "83.4" seconds as "1:23".
public func clock(_ seconds: Double) -> String {
    let total = Int(seconds.rounded(.down))
    return String(format: "%d:%02d", total / 60, total % 60)
}

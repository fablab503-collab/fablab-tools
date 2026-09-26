import Foundation

public struct Language: Identifiable, Hashable {
    public let code: String
    public let name: String
    public var id: String { code }
}

public enum Languages {
    public static let chatterbox: [Language] = [
        .init(code: "es", name: "Spanish"), .init(code: "en", name: "English"),
        .init(code: "pt", name: "Portuguese"), .init(code: "fr", name: "French"),
        .init(code: "de", name: "German"), .init(code: "it", name: "Italian"),
        .init(code: "nl", name: "Dutch"), .init(code: "pl", name: "Polish"),
        .init(code: "ru", name: "Russian"), .init(code: "tr", name: "Turkish"),
        .init(code: "ar", name: "Arabic"), .init(code: "hi", name: "Hindi"),
        .init(code: "zh", name: "Chinese"), .init(code: "ja", name: "Japanese"),
        .init(code: "ko", name: "Korean"), .init(code: "sv", name: "Swedish"),
        .init(code: "da", name: "Danish"), .init(code: "no", name: "Norwegian"),
        .init(code: "fi", name: "Finnish"), .init(code: "el", name: "Greek"),
        .init(code: "he", name: "Hebrew"), .init(code: "ms", name: "Malay"),
        .init(code: "sw", name: "Swahili"),
    ]

    public static let xtts: [Language] = [
        .init(code: "es", name: "Spanish"), .init(code: "en", name: "English"),
        .init(code: "pt", name: "Portuguese"), .init(code: "fr", name: "French"),
        .init(code: "de", name: "German"), .init(code: "it", name: "Italian"),
        .init(code: "nl", name: "Dutch"), .init(code: "pl", name: "Polish"),
        .init(code: "ru", name: "Russian"), .init(code: "tr", name: "Turkish"),
        .init(code: "ar", name: "Arabic"), .init(code: "hi", name: "Hindi"),
        .init(code: "zh", name: "Chinese"), .init(code: "ja", name: "Japanese"),
        .init(code: "ko", name: "Korean"), .init(code: "cs", name: "Czech"),
        .init(code: "hu", name: "Hungarian"),
    ]

    /// The languages this Mac's voice engine can speak.
    public static var available: [Language] { Engine.isAppleSilicon ? chatterbox : xtts }

    public static func name(_ code: String) -> String {
        (chatterbox + xtts).first { $0.code == code }?.name ?? code
    }
}

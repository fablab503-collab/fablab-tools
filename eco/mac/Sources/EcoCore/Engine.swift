import Foundation

/// Where the app keeps its private Python engine and models, and which voice this Mac gets.
public enum Engine {
    /// ~/Library/Application Support/Eco
    public static var home: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("Eco", isDirectory: true)
    }

    public static var engineDir: URL { home.appendingPathComponent("engine", isDirectory: true) }
    public static var python: URL { engineDir.appendingPathComponent("venv/bin/python") }
    public static var modelsDir: URL { home.appendingPathComponent("models", isDirectory: true) }
    /// Holds the app version the engine was installed for; a new app version reinstalls.
    public static var marker: URL { engineDir.appendingPathComponent("installed-version") }

    public static var isAppleSilicon: Bool {
        #if arch(arm64)
            return true
        #else
            return false
        #endif
    }

    /// Chatterbox on Apple Silicon; XTTS on Intel Macs, where PyTorch stops at 2.2.
    public static var voiceEngine: String { isAppleSilicon ? "chatterbox" : "xtts" }

    /// Intel Macs compile one part of the XTTS voice while installing, which needs Apple's
    /// Command Line Tools. (Apple silicon installs only ready-made packages.)
    public static var needsCommandLineTools: Bool { !isAppleSilicon && !hasCommandLineTools() }

    public static func hasCommandLineTools() -> Bool {
        let check = Process()
        check.executableURL = URL(fileURLWithPath: "/usr/bin/xcode-select")
        check.arguments = ["-p"]
        check.standardOutput = FileHandle.nullDevice
        check.standardError = FileHandle.nullDevice
        do {
            try check.run()
            check.waitUntilExit()
            return check.terminationStatus == 0
        } catch {
            return false
        }
    }

    /// Opens Apple's installer for the Command Line Tools.
    public static func installCommandLineTools() {
        let install = Process()
        install.executableURL = URL(fileURLWithPath: "/usr/bin/xcode-select")
        install.arguments = ["--install"]
        try? install.run()
    }

    public static func isInstalled(version: String) -> Bool {
        guard FileManager.default.isExecutableFile(atPath: python.path),
              let installed = try? String(contentsOf: marker, encoding: .utf8)
        else { return false }
        return installed.trimmingCharacters(in: .whitespacesAndNewlines) == version
    }

    public static func markInstalled(version: String) throws {
        try version.write(to: marker, atomically: true, encoding: .utf8)
    }

    /// The environment every engine process runs with.
    public static func environment(apiKey: String?, acceptedXTTSLicence: Bool) -> [String: String] {
        var env = ProcessInfo.processInfo.environment
        env["PYTHONUNBUFFERED"] = "1"
        env["PYTORCH_ENABLE_MPS_FALLBACK"] = "1"
        // Models live next to the engine, so removing Eco's folder removes all of it.
        env["HF_HOME"] = modelsDir.appendingPathComponent("huggingface").path
        env["TORCH_HOME"] = modelsDir.appendingPathComponent("torch").path
        env["TTS_HOME"] = modelsDir.appendingPathComponent("tts").path
        env["ARGOS_TRANSLATE_PACKAGE_DIR"] = modelsDir.appendingPathComponent("argos").path
        env["PATH"] = "/usr/bin:/bin:/usr/sbin:/sbin:/opt/homebrew/bin:/usr/local/bin"
        if let apiKey, !apiKey.isEmpty { env["ANTHROPIC_API_KEY"] = apiKey }
        if acceptedXTTSLicence { env["COQUI_TOS_AGREED"] = "1" }
        return env
    }
}

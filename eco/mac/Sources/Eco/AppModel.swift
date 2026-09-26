import AppKit
import EcoCore
import SwiftUI

/// Settings shown in the Settings window, stored in UserDefaults under these keys.
enum Pref {
    static let translator = "translator"          // "claude" or "argos"
    static let style = "style"                    // "line" or "steady"
    static let maxSpeed = "maxSpeed"
    static let exaggeration = "exaggeration"
    static let quality = "quality"                // "best" or "fast"
    static let noMusic = "noMusic"
    static let acceptedXTTSLicence = "acceptedXTTSLicence"
    static let languages = "languages"

    static func register() {
        UserDefaults.standard.register(defaults: [
            translator: "claude", style: "line", maxSpeed: 1.25, exaggeration: 0.5,
            // Intel Macs run everything on the processor, so they default to the faster models.
            quality: Engine.isAppleSilicon ? "best" : "fast",
            noMusic: false, acceptedXTTSLicence: false, languages: "es",
        ])
    }
}

final class AppModel: ObservableObject {
    enum Screen { case setup, main, review, finished }

    @Published var screen: Screen
    @Published var busy = false
    @Published var stepText = ""
    @Published var progress: Double?
    @Published var log: [String] = []
    @Published var errorMessage: String?

    @Published var video: URL? { didSet { if video != oldValue { files = [] } } }
    @Published var selected: Set<String> {
        didSet { UserDefaults.standard.set(selected.sorted().joined(separator: ","), forKey: Pref.languages) }
    }
    @Published var notes = ""
    @Published var files: [URL] = []

    // Review
    @Published var transcript: Transcript?
    @Published var scripts: [LanguageScript] = []

    private var runner: Runner?
    private let defaults = UserDefaults.standard

    /// The engine is reinstalled whenever the app's build number changes.
    let engineVersion = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "dev"

    init() {
        Pref.register()
        selected = Set((UserDefaults.standard.string(forKey: Pref.languages) ?? "es")
            .split(separator: ",").map(String.init))
        screen = Engine.isInstalled(version: engineVersion) ? .main : .setup
    }

    var workFolder: WorkFolder? { video.map { WorkFolder(video: $0) } }

    var needsAPIKey: Bool {
        defaults.string(forKey: Pref.translator) == "claude" && (Keychain.apiKey() ?? "").isEmpty
    }

    var hasPreviousWork: Bool {
        guard let folder = workFolder else { return false }
        return !folder.languages().isEmpty
    }

    // MARK: Running the engine

    private func run(_ executable: URL, _ arguments: [String], then finish: @escaping (Bool) -> Void) {
        busy = true
        log = []
        errorMessage = nil
        progress = nil
        stepText = "Starting…"
        let runner = Runner(
            executable: executable, arguments: arguments,
            environment: Engine.environment(
                apiKey: Keychain.apiKey(),
                acceptedXTTSLicence: defaults.bool(forKey: Pref.acceptedXTTSLicence)))
        self.runner = runner
        do {
            try runner.start(
                onLine: { [weak self] line in self?.handle(line) },
                onExit: { [weak self] status in
                    guard let self else { return }
                    self.busy = false
                    self.runner = nil
                    self.progress = nil
                    if status != 0 && self.errorMessage == nil {
                        self.errorMessage = status == 130
                            ? "Stopped. Start again to carry on where it left off."
                            : "Something went wrong. Open Details below to see what happened."
                    }
                    finish(status == 0)
                })
        } catch {
            busy = false
            self.runner = nil
            errorMessage = "Could not start the engine: \(error.localizedDescription)"
        }
    }

    private func handle(_ line: String) {
        guard let event = EcoEvent.parse(line) else {
            log.append(line)
            if log.count > 3000 { log.removeFirst(log.count - 3000) }
            return
        }
        switch event.event {
        case "step":
            stepText = event.text ?? stepText
            progress = nil
        case "progress":
            if let done = event.done, let total = event.total, total > 0 {
                progress = Double(done) / Double(total)
                let language = event.language.map { Languages.name($0) } ?? ""
                stepText = "Voicing \(language): line \(done) of \(total)"
            }
        case "error":
            errorMessage = event.message
        case "done":
            files = (event.files ?? []).map { URL(fileURLWithPath: $0) }
        default:
            break
        }
    }

    func stop() { runner?.stop() }

    // MARK: Setup

    func install() {
        guard let resources = Bundle.main.resourceURL else { return }
        do {
            try FileManager.default.createDirectory(at: Engine.engineDir, withIntermediateDirectories: true)
        } catch {
            errorMessage = error.localizedDescription
            return
        }
        let script = resources.appendingPathComponent("install-engine.sh")
        let uv = resources.appendingPathComponent("uv")
        let wheels = (try? FileManager.default.contentsOfDirectory(
            at: resources.appendingPathComponent("engine"), includingPropertiesForKeys: nil)) ?? []
        guard let wheel = wheels.first(where: { $0.pathExtension == "whl" }) else {
            errorMessage = "This copy of Eco is incomplete (its engine package is missing). Download it again."
            return
        }
        run(URL(fileURLWithPath: "/bin/bash"), [script.path, uv.path, Engine.engineDir.path, wheel.path]) { ok in
            guard ok else { return }
            self.run(Engine.python, ["-m", "eco.prefetch", "--progress-json"] + self.modelOptions) { ok in
                guard ok else { return }
                try? Engine.markInstalled(version: self.engineVersion)
                self.screen = .main
            }
        }
    }

    // MARK: Dubbing

    private var modelOptions: [String] {
        defaults.string(forKey: Pref.quality) == "fast"
            ? ["--whisper-model", "large-v3-turbo", "--separator-model", "htdemucs"]
            : ["--whisper-model", "large-v3", "--separator-model", "htdemucs_ft"]
    }

    private func dubArguments(review: Bool) -> [String]? {
        guard let video, !selected.isEmpty else { return nil }
        let languages = Languages.available.map(\.code).filter { selected.contains($0) }
        var args = ["-m", "eco", video.path, "--to", languages.joined(separator: ","), "--progress-json"]
        if review { args.append("--review") }
        if !notes.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { args += ["--notes", notes] }
        args += ["--translator", defaults.string(forKey: Pref.translator) ?? "claude"]
        args += ["--style", defaults.string(forKey: Pref.style) ?? "line"]
        args += ["--max-speed", String(defaults.double(forKey: Pref.maxSpeed))]
        args += ["--engine", Engine.voiceEngine]
        if Engine.isAppleSilicon { args += ["--exaggeration", String(defaults.double(forKey: Pref.exaggeration))] }
        if defaults.bool(forKey: Pref.noMusic) { args.append("--no-separate") }
        return args + modelOptions
    }

    /// Transcribe and translate, then stop so every line can be checked.
    func translate() {
        guard let args = dubArguments(review: true) else { return }
        run(Engine.python, args) { ok in
            if ok { self.openReview() }
        }
    }

    /// Voice and mix everything (translating first whatever is not translated yet).
    func dub() {
        guard let args = dubArguments(review: false) else { return }
        run(Engine.python, args) { ok in
            if ok { self.screen = .finished }
        }
    }

    // MARK: Review

    func openReview() {
        guard let folder = workFolder else { return }
        transcript = try? folder.transcript()
        scripts = folder.languages().compactMap { try? folder.script($0) }
        errorMessage = scripts.isEmpty ? "Nothing to review yet: translate the video first." : nil
        if !scripts.isEmpty { screen = .review }
    }

    func saveReview() -> Bool {
        guard let folder = workFolder else { return false }
        do {
            if let transcript { try folder.save(transcript) }
            for script in scripts { try folder.save(script) }
            return true
        } catch {
            errorMessage = "Could not save: \(error.localizedDescription)"
            return false
        }
    }

    func dubReviewed() {
        guard saveReview() else { return }
        selected.formUnion(scripts.map(\.language))
        screen = .main
        dub()
    }

    // MARK: Finished

    func showInFinder() {
        NSWorkspace.shared.activateFileViewerSelecting(files)
    }

    func startOver() {
        video = nil
        files = []
        transcript = nil
        scripts = []
        screen = .main
    }
}

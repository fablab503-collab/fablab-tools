import Foundation

/// Runs one engine command and reports its output line by line on the main queue.
public final class Runner {
    public typealias LineHandler = (String) -> Void
    public typealias ExitHandler = (Int32) -> Void

    private let process = Process()
    private var buffer = LineBuffer()
    private let queue = DispatchQueue(label: "com.danielmadac.Eco.runner")

    public init(executable: URL, arguments: [String], environment: [String: String],
                directory: URL? = nil) {
        process.executableURL = executable
        process.arguments = arguments
        process.environment = environment
        if let directory { process.currentDirectoryURL = directory }
    }

    /// Starts the process. `onLine` gets every line of stdout and stderr, in order, then
    /// `onExit` the exit status; both on the main queue.
    public func start(onLine: @escaping LineHandler, onExit: @escaping ExitHandler) throws {
        let pipe = Pipe()
        let reader = pipe.fileHandleForReading
        process.standardOutput = pipe
        process.standardError = pipe
        // The runner keeps itself alive through these closures until the process has ended.
        reader.readabilityHandler = { handle in
            let data = handle.availableData
            guard !data.isEmpty else { return }
            self.queue.async {
                let lines = self.buffer.append(data)
                DispatchQueue.main.async { lines.forEach(onLine) }
            }
        }
        process.terminationHandler = { process in
            reader.readabilityHandler = nil
            let status = process.terminationStatus
            self.queue.async {
                let rest = (try? reader.readToEnd()) ?? Data()
                let lines = self.buffer.append(rest) + self.buffer.flush()
                DispatchQueue.main.async {
                    lines.forEach(onLine)
                    onExit(status)
                }
            }
        }
        try process.run()
    }

    public var isRunning: Bool { process.isRunning }

    /// Ctrl-C, not a kill: the engine keeps its place and a second run carries on from there.
    public func stop() {
        if process.isRunning { process.interrupt() }
    }
}

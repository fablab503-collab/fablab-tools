import XCTest

@testable import EcoCore

final class EventTests: XCTestCase {
    func testParsesEngineEvents() throws {
        let event = try XCTUnwrap(EcoEvent.parse(#"@eco {"event": "progress", "language": "es", "done": 3, "total": 12}"#))
        XCTAssertEqual(event.event, "progress")
        XCTAssertEqual(event.language, "es")
        XCTAssertEqual(event.done, 3)
        XCTAssertEqual(event.total, 12)

        let done = try XCTUnwrap(EcoEvent.parse(#"@eco {"event": "done", "work": "/v/a.eco", "files": ["/v/a.es.mp4"]}"#))
        XCTAssertEqual(done.files, ["/v/a.es.mp4"])
    }

    func testOrdinaryLinesAreNotEvents() {
        XCTAssertNil(EcoEvent.parse("== Transcribing"))
        XCTAssertNil(EcoEvent.parse("@eco not json"))
    }

    func testLineBufferKeepsPartialLines() {
        var buffer = LineBuffer()
        XCTAssertEqual(buffer.append(Data("one\ntw".utf8)), ["one"])
        XCTAssertEqual(buffer.append(Data("o\r\nthree".utf8)), ["two"])
        XCTAssertEqual(buffer.flush(), ["three"])
        XCTAssertEqual(buffer.flush(), [])
    }
}

final class WorkFolderTests: XCTestCase {
    private var folder: WorkFolder!

    override func setUpWithError() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".eco")
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        folder = WorkFolder(url: url)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: folder.url)
    }

    func testFolderIsNamedAfterTheVideo() {
        XCTAssertEqual(WorkFolder(video: URL(fileURLWithPath: "/Movies/My Ride.mp4")).url.path, "/Movies/My Ride.eco")
    }

    /// The files are written by the Python engine; the app must read them as they are and
    /// write them back in a form the engine still reads, keeping `machine` and `take`.
    func testRoundTripsTheEnginesFiles() throws {
        let engineJSON = """
        {
          "language": "es",
          "lines": [
            {"id": 1, "start": 0.5, "end": 1.8, "source": "Hello everyone.", "text": "Hola a todos.",
             "machine": "Hola a todos.", "take": 1},
            {"id": 2, "start": 3.0, "end": 4.6, "source": "Today we ride.", "text": "Hoy rodamos."}
          ]
        }
        """
        try Data(engineJSON.utf8).write(to: folder.scriptURL("es"))
        try Data(#"{"language": "en", "lines": [{"id": 1, "start": 0.5, "end": 1.8, "text": "Hello everyone."}]}"#.utf8)
            .write(to: folder.transcriptURL)

        XCTAssertEqual(folder.languages(), ["es"])
        var script = try folder.script("es")
        XCTAssertFalse(script.lines[0].editedByHand)
        script.lines[0].text = "¡Hola a todos!"
        script.lines[1].take = 2
        try folder.save(script)

        let reread = try folder.script("es")
        XCTAssertEqual(reread.lines[0].text, "¡Hola a todos!")
        XCTAssertEqual(reread.lines[0].machine, "Hola a todos.")
        XCTAssertTrue(reread.lines[0].editedByHand)
        XCTAssertEqual(reread.lines[1].take, 2)
        XCTAssertNil(reread.lines[1].machine)

        let raw = try JSONSerialization.jsonObject(with: Data(contentsOf: folder.scriptURL("es"))) as? [String: Any]
        let lines = try XCTUnwrap(raw?["lines"] as? [[String: Any]])
        XCTAssertEqual(lines[0]["machine"] as? String, "Hola a todos.")
        XCTAssertEqual(try folder.transcript().lines.first?.text, "Hello everyone.")
    }

    func testClock() {
        XCTAssertEqual(clock(0), "0:00")
        XCTAssertEqual(clock(83.9), "1:23")
    }
}

final class EngineTests: XCTestCase {
    func testEachMacGetsItsVoice() {
        #if arch(arm64)
            XCTAssertEqual(Engine.voiceEngine, "chatterbox")
            XCTAssertEqual(Languages.available.count, 23)
        #else
            XCTAssertEqual(Engine.voiceEngine, "xtts")
            XCTAssertEqual(Languages.available.count, 17)
        #endif
    }

    func testEnvironmentCarriesKeyAndLicenceOnlyWhenGiven() {
        let without = Engine.environment(apiKey: nil, acceptedXTTSLicence: false)
        XCTAssertNil(without["COQUI_TOS_AGREED"])
        let with = Engine.environment(apiKey: "sk-test", acceptedXTTSLicence: true)
        XCTAssertEqual(with["ANTHROPIC_API_KEY"], "sk-test")
        XCTAssertEqual(with["COQUI_TOS_AGREED"], "1")
        XCTAssertTrue(with["HF_HOME"]!.hasSuffix("Eco/models/huggingface"))
    }
}

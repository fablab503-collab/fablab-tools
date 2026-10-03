import XCTest

/// Bouclier on one simulated iPhone, set up the way a person does it: Bouclier's app window, the extension
/// turned on in Settings with access to every website, the independent test page in Safari, and Bouclier's
/// menu in Safari. Each step attaches a screenshot and the screen's accessibility tree to the test result
/// (tests/iphone-ui/run.sh exports them), so a step that fails on one iOS version shows what was on screen.
final class BouclierOnIPhone: XCTestCase {
    let bouclier = XCUIApplication(bundleIdentifier: "com.danielmadac.Bouclier")
    let settings = XCUIApplication(bundleIdentifier: "com.apple.Preferences")
    let safari = XCUIApplication(bundleIdentifier: "com.apple.mobilesafari")
    let testPage = "https://adblock.turtlecute.org/"

    override func setUp() {
        continueAfterFailure = true
    }

    // MARK: - the steps

    func test1_AppWindow() {
        bouclier.terminate()
        bouclier.launch()
        XCTAssertTrue(bouclier.wait(for: .runningForeground, timeout: 15), "Bouclier's app opens")
        sleep(3)
        shot("1-app", bouclier)
        // the app window is a web page: its texts are in the tree
        XCTAssertTrue(bouclier.webViews.firstMatch.waitForExistence(timeout: 10), "the app window shows its page")
    }

    func test2_TurnOnInSettings() {
        settings.terminate()
        settings.launch()
        sleep(2)
        shot("2a-settings", settings)
        // iOS 18 and later: Settings > Apps > Safari > Extensions > Bouclier; iOS 16 and 17: Settings > Safari > …
        let ios18 = ProcessInfo.processInfo.operatingSystemVersion.majorVersion >= 18
        let found = (!ios18 || tapRow(settings, ["Apps"])) && tapRow(settings, ["Safari", "com.apple.mobilesafari"])
            && tapRow(settings, ["Extensions"]) && tapRow(settings, ["Bouclier", "com.danielmadac.Bouclier.Extension"])
        XCTAssertTrue(found, ios18 ? "Settings > Apps > Safari > Extensions > Bouclier" : "Settings > Safari > Extensions > Bouclier")
        sleep(1)
        shot("2b-bouclier-settings", settings)
        // the extension's own switch ("Allow Extension"; on iOS 26 the row is a switch with the toggle at its right end)
        let named = settings.switches.matching(identifier: "Allow Extension").firstMatch
        let toggle = named.exists ? named : settings.switches.firstMatch
        if toggle.waitForExistence(timeout: 5) {
            if (toggle.value as? String) != "1" {
                toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
                sleep(2)
            }
            XCTAssertEqual(toggle.value as? String, "1", "Bouclier is on in Safari")
        } else {
            XCTFail("no switch for Bouclier")
        }
        shot("2c-switched-on", settings)
        // access to every website: All Websites > Allow
        if tapRow(settings, ["All Websites", "Other Websites"], swipes: 3) {
            sleep(1)
            shot("2e-all-websites", settings)
            XCTAssertTrue(tapRow(settings, ["Allow"], swipes: 2), "All Websites: Allow")
            sleep(1)
        } else {
            XCTFail("no All Websites row")
        }
        shot("2d-bouclier-on", settings)
    }

    func test3_TestPageInSafari() {
        safari.terminate()
        XCTAssertTrue(openPage(testPage), "Safari opens the test page")
        // Safari compiles Bouclier's rules once after it is turned on: give it time, then run the test again
        sleep(45)
        shot("3a-first-load", safari)
        if let again = element(safari, ["Re-test ad blocker", "Re-test"]) {
            if !again.isHittable { safari.swipeDown(velocity: .slow) }
            if again.isHittable { again.tap() }
        }
        sleep(50)   // the page tries every address before it gives its score
        shot("3b-test-page", safari)
        safari.swipeUp(velocity: .slow)
        sleep(1)
        shot("3c-test-page-scrolled", safari)
        let score = texts(safari).filter { $0.localizedCaseInsensitiveContains("blocked") || $0.contains("%") || $0.hasPrefix("Total") }.prefix(12)
        let note = XCTAttachment(string: score.joined(separator: "\n")); note.name = "3-score-texts"; note.lifetime = .keepAlways; add(note)
    }

    func test4_MenuInSafari() {
        if safari.state != .runningForeground || !safari.staticTexts.matching(NSPredicate(format: "label CONTAINS[c] %@", "Ad Blocker Test")).firstMatch.exists {
            _ = openPage(testPage)
            sleep(20)
        }
        dismissFirstRun(safari)
        showBars(safari)
        shot("4a-safari", safari)
        // the page menu in the address bar ("AA" before iOS 26) lists the extensions
        let opened = tapAny(safari, ["PageFormatMenuButton", "Page Menu", "Page menu", "Show Page Menu", "More", "Show More", "Extensions"])
        XCTAssertTrue(opened, "Safari's page menu opens")
        sleep(1)
        shot("4b-page-menu", safari)
        var menu = tapAny(safari, ["Bouclier", "Bouclier – Ad Blocker", "Bouclier – Bloqueur de pub"])
        if !menu, tapAny(safari, ["Extensions", "Manage Extensions"]) {
            sleep(1)
            shot("4c-extensions", safari)
            menu = tapAny(safari, ["Bouclier", "Bouclier – Ad Blocker"])
        }
        XCTAssertTrue(menu, "Bouclier's menu opens from Safari")
        sleep(5)
        shot("4d-bouclier-menu", safari)
        let shown = texts(safari)
        let note = XCTAttachment(string: shown.joined(separator: "\n")); note.name = "4-menu-texts"; note.lifetime = .keepAlways; add(note)
        // the menu's own words (the test page also says "blocked", so that word alone proves nothing)
        let marks = ["on this page", "blocked today", "sur cette page", "aujourd’hui"]
        let menuShown = shown.contains { text in marks.contains { text.localizedCaseInsensitiveContains($0) } }
        if #available(iOS 26, *) {
            XCTAssertTrue(menuShown, "the menu says how much was blocked on this page")
        } else if !menuShown {
            // before iOS 26 the menu's page is not in Safari's accessibility tree: the 4d screenshot shows it
            let why = XCTAttachment(string: "menu texts not readable on this iOS version: see 4d-bouclier-menu.png")
            why.name = "4-menu-note"; why.lifetime = .keepAlways; add(why)
        }
    }

    // MARK: - helpers

    func shot(_ name: String, _ app: XCUIApplication) {
        let image = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        image.name = name
        image.lifetime = .keepAlways
        add(image)
        let tree = XCTAttachment(string: app.debugDescription)
        tree.name = name + "-tree"
        tree.lifetime = .keepAlways
        add(tree)
    }

    /// The texts on screen, read from one snapshot of the app: a page that changes while they are read
    /// one by one makes XCUITest fail ("No matches found for Element at index 34", iOS 27).
    func texts(_ app: XCUIApplication, limit: Int = 200) -> [String] {
        guard let root = try? app.snapshot() else { return [] }
        var out: [String] = []
        var queue: [XCUIElementSnapshot] = [root]
        var i = 0
        while i < queue.count && out.count < limit {
            let node = queue[i]
            i += 1
            if node.elementType == .staticText && !node.label.isEmpty { out.append(node.label) }
            queue.append(contentsOf: node.children)
        }
        return out
    }

    /// An element with one of these labels (cells and buttons first, as Settings shows rows): the first one
    /// that can be tapped, else the first one that exists. (iOS 18's Settings keeps rows that are not on
    /// screen in its tree, so the first match is not always the visible one.)
    func element(_ app: XCUIApplication, _ labels: [String]) -> XCUIElement? {
        let types: [XCUIElement.ElementType] = [.cell, .button, .staticText, .link, .menuItem, .other]
        var fallback: XCUIElement?
        for label in labels {
            for type in types {
                let matches = app.descendants(matching: type).matching(NSPredicate(format: "label == %@ OR identifier == %@", label, label))
                for e in matches.allElementsBoundByIndex.prefix(8) where e.exists {
                    if e.isHittable { return e }
                    if fallback == nil { fallback = e }
                }
            }
        }
        return fallback
    }

    /// Scrolls down until a row with one of these labels can be tapped, then taps it. A tap while the list
    /// still glides after a swipe only stops it (iPhone 13 mini, iOS 18.5): the list is given a second to
    /// settle, and when the screen did not change the row is tapped once more.
    @discardableResult
    func tapRow(_ app: XCUIApplication, _ labels: [String], swipes: Int = 14) -> Bool {
        for _ in 0...swipes {
            if let e = element(app, labels), e.isHittable {
                let screen = app.navigationBars.firstMatch.identifier
                e.tap()
                sleep(2)
                if app.navigationBars.firstMatch.identifier == screen, let again = element(app, labels), again.isHittable {
                    again.tap()
                    sleep(2)
                }
                return true
            }
            app.swipeUp(velocity: .slow)
            sleep(1)
        }
        return false
    }

    /// Taps the first hittable element with one of these labels, without scrolling.
    @discardableResult
    func tapAny(_ app: XCUIApplication, _ labels: [String]) -> Bool {
        for label in labels {
            if let e = element(app, [label]), e.isHittable {
                e.tap()
                return true
            }
        }
        return false
    }

    /// What Safari's address field shows (empty on the start page).
    func addressValue(_ app: XCUIApplication) -> String {
        let field = app.textFields["TabBarItemTitle"]
        return field.exists ? ((field.value as? String) ?? "") : ""
    }

    /// Opens a page in Safari and waits until a text of that page shows: through the system first, then by
    /// typing the address (iOS 26 and 27 can stay on the start page on a first launch, the address already
    /// in the bar, so the address alone does not tell the page is there).
    @discardableResult
    func openPage(_ url: String, expect: String = "Ad Blocker Test") -> Bool {
        let host = URL(string: url)!.host!
        let shown = { self.safari.staticTexts.matching(NSPredicate(format: "label CONTAINS[c] %@", expect)).firstMatch.exists }
        XCUIDevice.shared.system.open(URL(string: url)!)
        guard safari.wait(for: .runningForeground, timeout: 20) else { return false }
        sleep(4)
        dismissFirstRun(safari)
        for attempt in 1...3 {
            for _ in 0..<15 where !shown() { sleep(1) }
            if shown() { return true }
            showBars(safari)
            let field = safari.textFields["TabBarItemTitle"]
            guard field.waitForExistence(timeout: 5) else { continue }
            field.tap()
            sleep(1)
            let clear = safari.buttons.matching(NSPredicate(format: "label IN %@", ["Clear text", "Clear", "Effacer le texte"])).firstMatch
            if clear.exists && clear.isHittable { clear.tap() }
            safari.typeText(url)
            let go = safari.keyboards.buttons.matching(NSPredicate(format: "label IN %@", ["Go", "go", "Aller", "Open", "Return", "return"])).firstMatch
            if go.exists { go.tap() } else { safari.typeText("\n") }
            sleep(3)
            shot("open-\(host)-\(attempt)", safari)
        }
        for _ in 0..<15 where !shown() { sleep(1) }
        return shown()
    }

    /// Safari hides its address bar while a page scrolls: scroll back up and tap the small bar at the bottom.
    func showBars(_ app: XCUIApplication) {
        let menuButton = app.buttons["PageFormatMenuButton"]
        if menuButton.exists && menuButton.isHittable { return }
        app.swipeDown(velocity: .slow)
        sleep(1)
        if menuButton.exists && menuButton.isHittable { return }
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.965)).tap()
        sleep(1)
    }

    /// Safari's first-run screens (what's new, tab layout): continue with the defaults. ("Close" is left out:
    /// on iOS 27 it is also the label of the button that stops a page loading, gone a moment later.)
    func dismissFirstRun(_ app: XCUIApplication) {
        for _ in 0..<3 {
            if !tapAny(app, ["Continue", "Not Now", "OK", "Done"]) { break }
            sleep(1)
        }
    }
}

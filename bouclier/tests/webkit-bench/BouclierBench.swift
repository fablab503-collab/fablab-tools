// BouclierBench — loads web pages in WebKit (the engine inside Safari), with or without a web
// extension (Bouclier's extension/ folder), and records every resource each page and each of its
// frames loaded, plus what the page shows (ad slots, consent pop-ups, "turn off your ad blocker").
// Blocked requests never start, so they are simply absent. One JSON line per page.
//
//   BouclierBench.app/Contents/MacOS/BouclierBench --sites ../safari-sites/sites.tsv --out run.jsonl
//       [--extension ../../extension] [--parallel 3] [--first 1] [--last 999]
//       [--probe ../safari-sites/probe.js] [--settle 4] [--label v130] [--no-consent] [--probe-world client]
//
// By default the benchmark accepts the consent pop-up the way most visitors do (the usual consent
// tools: Didomi, OneTrust, Sourcepoint, Quantcast, Funding Choices, Axeptio, Cookiebot...), because
// most ads only load after consent. --no-consent measures first visits that never answer it.
//
// Pages run in windows placed off-screen (nothing shows on the desktop); each page gets its own
// throw-away website data store, so no cookie or consent carries over between pages or runs.

import AppKit
import WebKit

// MARK: - Options

struct Options {
    var sites = "sites.tsv"
    var out = "out.jsonl"
    var extensionPath: String?
    var parallel = 3
    var first = 1
    var last = Int.max
    var probe = "probe.js"
    var settle = 4.0
    var navTimeout = 25.0
    var label = ""
    var safariVersion = "26.0"
    var acceptConsent = true
    var probeWorld = "page"

    static func parse() -> Options {
        var o = Options()
        var args = Array(CommandLine.arguments.dropFirst())
        func value() -> String { args.isEmpty ? "" : args.removeFirst() }
        while !args.isEmpty {
            let a = args.removeFirst()
            switch a {
            case "--sites": o.sites = value()
            case "--out": o.out = value()
            case "--extension": o.extensionPath = value()
            case "--parallel": o.parallel = max(1, Int(value()) ?? 3)
            case "--first": o.first = Int(value()) ?? 1
            case "--last": o.last = Int(value()) ?? Int.max
            case "--probe": o.probe = value()
            case "--settle": o.settle = Double(value()) ?? 4
            case "--timeout": o.navTimeout = Double(value()) ?? 25
            case "--label": o.label = value()
            case "--safari-version": o.safariVersion = value()
            case "--no-consent": o.acceptConsent = false
            case "--probe-world": o.probeWorld = value()
            default:
                FileHandle.standardError.write("unknown option \(a)\n".data(using: .utf8)!)
                exit(64)
            }
        }
        return o
    }
}

struct Site {
    let n: Int
    let category: String
    let url: URL
}

func log(_ text: String) {
    let stamp = ISO8601DateFormatter().string(from: Date())
    FileHandle.standardError.write("[\(stamp)] \(text)\n".data(using: .utf8)!)
}

// MARK: - Tabs and windows the extension can see

@MainActor /// Clicks "accept" in consent pop-ups (top page and consent iframes), like most visitors do.
let consentScript = """
(() => {
  const KNOWN = ['#didomi-notice-agree-button', '#onetrust-accept-btn-handler', 'button.sp_choice_type_11',
    '.qc-cmp2-summary-buttons button[mode="primary"]', 'button.fc-cta-consent', '#axeptio_btn_acceptAll',
    '#CybotCookiebotDialogBodyLevelButtonLevelOptinAllowAll', '#CybotCookiebotDialogBodyButtonAccept',
    '#truste-consent-button', '.cmp-intro_acceptAll', '#footer_tc_privacy_button', '#popin_tc_privacy_button_2',
    '#sd-cmp button.sd-cmp-JnaLO', 'button[data-testid="uc-accept-all-button"]', '#accept-choices', '.fc-button.fc-cta-consent',
    'button.css-1k47zha', '#cmpbntyestxt', '.cmpboxbtnyes', '#iubenda-cs-accept-btn', '.iubenda-cs-accept-btn',
    'button[aria-label="Accept all"]', 'button[aria-label="Tout accepter"]', 'button[aria-label="Accepter et fermer"]'];
  const WORDS = /^(tout accepter|accepter et fermer|accepter & fermer|accepter tout|tout accepter et fermer|accepter|j.accepte|je suis d.accord|accept all|accept all cookies|accept cookies|accept|i accept|i agree|agree|agree and close|allow all|allow all cookies|allow cookies|yes, i.m happy|alle akzeptieren|akzeptieren|zustimmen|aceptar|aceptar todo|aceptar y continuar|accetta|accetta tutto|ok, i understand|got it)$/i;
  const CONSENT_BOX = /consent|cookie|cmp|gdpr|rgpd|privacy|didomi|onetrust|sp_message|qc-cmp|fc-consent|axeptio|cybot|usercentrics|truste|sd-cmp|tc-privacy|appconsent|cmpbox|iubenda|notice/i;
  const inFrameCmp = window !== window.top && /consent|cmp|privacy-mgmt|sourcepoint|sp-prod|didomi|onetrust|quantcast|fundingchoices|appconsent|sddan|axept|cookielaw|trustarc|usercentrics|consentmanager/i.test(location.href);
  const visible = el => { const r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0 && getComputedStyle(el).visibility !== 'hidden'; };
  const boxOf = el => { for (let n = el, i = 0; n && i < 12; n = n.parentElement, i++) { if (CONSENT_BOX.test((n.id || '') + ' ' + (typeof n.className === 'string' ? n.className : ''))) return true; } return false; };
  let tries = 0, done = false;
  const attempt = () => {
    if (done) return;
    for (const sel of KNOWN) {
      const el = document.querySelector(sel);
      if (el && visible(el)) { el.click(); done = true; return; }
    }
    if (window !== window.top && !inFrameCmp) return;
    for (const b of document.querySelectorAll('button, [role="button"], a[role="button"], input[type="button"], input[type="submit"]')) {
      const t = (b.innerText || b.value || b.getAttribute('aria-label') || '').trim().replace(/\\s+/g, ' ');
      if (t.length <= 40 && WORDS.test(t) && visible(b) && (inFrameCmp || boxOf(b))) { b.click(); done = true; return; }
    }
  };
  const timer = setInterval(() => { tries++; attempt(); if (done || tries > 50) clearInterval(timer); }, 400);
})();
"""

@MainActor final class BenchWindow: NSObject, WKWebExtensionWindow {
    var tabs: [BenchTab] = []

    func tabs(for context: WKWebExtensionContext) -> [any WKWebExtensionTab] { tabs }
    func activeTab(for context: WKWebExtensionContext) -> (any WKWebExtensionTab)? { tabs.last }
    func windowType(for context: WKWebExtensionContext) -> WKWebExtension.WindowType { .normal }
    func windowState(for context: WKWebExtensionContext) -> WKWebExtension.WindowState { .normal }
    func isPrivate(for context: WKWebExtensionContext) -> Bool { false }
    func frame(for context: WKWebExtensionContext) -> CGRect { CGRect(x: 40, y: 40, width: 1440, height: 1000) }
    func screenFrame(for context: WKWebExtensionContext) -> CGRect { NSScreen.main?.frame ?? CGRect(x: 0, y: 0, width: 1512, height: 982) }
}

@MainActor final class BenchTab: NSObject, WKWebExtensionTab {
    let webView: WKWebView
    weak var window: BenchWindow?

    init(webView: WKWebView, window: BenchWindow) {
        self.webView = webView
        self.window = window
    }

    func window(for context: WKWebExtensionContext) -> (any WKWebExtensionWindow)? { window }
    func indexInWindow(for context: WKWebExtensionContext) -> Int { window?.tabs.firstIndex { $0 === self } ?? 0 }
    func webView(for context: WKWebExtensionContext) -> WKWebView? { webView }
    func title(for context: WKWebExtensionContext) -> String? { webView.title }
    func url(for context: WKWebExtensionContext) -> URL? { webView.url }
    func isLoadingComplete(for context: WKWebExtensionContext) -> Bool { !webView.isLoading }
    func isSelected(for context: WKWebExtensionContext) -> Bool { true }
    func size(for context: WKWebExtensionContext) -> CGSize { webView.bounds.size }
    func shouldGrantPermissionsOnUserGesture(for context: WKWebExtensionContext) -> Bool { true }
}

// MARK: - The benchmark

/// Runs in every frame at document start (in its own JavaScript world, invisible to the page):
/// reports each resource the frame loads, including those of ad iframes.
let captureScript = """
(() => {
  try { performance.setResourceTimingBufferSize(1000000); } catch (e) {}
  const post = (msg) => { try { webkit.messageHandlers.bench.postMessage(msg); } catch (e) {} };
  const seen = new Set();
  const flush = (entries) => {
    const out = [];
    for (const e of entries) {
      const k = e.name + '|' + e.startTime;
      if (seen.has(k)) continue;
      seen.add(k);
      out.push([String(e.name).slice(0, 600), e.initiatorType || '', Math.round(e.startTime)]);
    }
    if (out.length) post({ frame: String(location.href).slice(0, 300), top: window === window.top, res: out });
  };
  try { new PerformanceObserver(l => flush(l.getEntries())).observe({ type: 'resource', buffered: true }); } catch (e) {}
  post({ frame: String(location.href).slice(0, 300), top: window === window.top, res: [], start: true });
})();
"""

@MainActor final class Bench: NSObject, WKScriptMessageHandler, WKWebExtensionControllerDelegate, WKUIDelegate {
    let options: Options
    let probeSource: String
    var controller: WKWebExtensionController?
    var context: WKWebExtensionContext?
    let window = BenchWindow()
    var captured: [ObjectIdentifier: [[String]]] = [:]
    var frames: [ObjectIdentifier: Set<String>] = [:]
    var popups: [ObjectIdentifier: [String]] = [:]
    var nextIndex = 0
    var sites: [Site] = []
    var out: FileHandle?
    var slot = 0

    init(options: Options) {
        self.options = options
        self.probeSource = (try? String(contentsOfFile: options.probe, encoding: .utf8)) ?? "JSON.stringify({error: 'no probe'})"
    }

    // MARK: extension

    func loadExtension(_ path: String) async throws {
        let url = URL(fileURLWithPath: path, isDirectory: true).standardizedFileURL
        let ext = try await WKWebExtension(resourceBaseURL: url)
        for e in ext.errors { log("extension warning: \(e.localizedDescription)") }
        let ctx = WKWebExtensionContext(for: ext)
        ctx.isInspectable = true
        ctx.uniqueIdentifier = "bench-\(options.label.isEmpty ? "ext" : options.label)"
        for p in ext.requestedPermissions { ctx.setPermissionStatus(.grantedExplicitly, for: p) }
        for p in ext.optionalPermissions { ctx.setPermissionStatus(.grantedExplicitly, for: p) }
        for m in ext.allRequestedMatchPatterns { ctx.setPermissionStatus(.grantedExplicitly, for: m) }
        ctx.setPermissionStatus(.grantedExplicitly, for: WKWebExtension.MatchPattern.allURLs())
        ctx.hasAccessToPrivateData = true
        let ctl = WKWebExtensionController(configuration: .nonPersistent())
        ctl.delegate = self
        try ctl.load(ctx)
        controller = ctl
        context = ctx
        ctl.didOpenWindow(window)
        ctl.didFocusWindow(window)
        log("extension \(ext.displayName ?? "?") \(ext.version ?? "?") loaded; all URLs: \(ctx.hasAccessToAllURLs)")
        for e in ctx.errors { log("extension error: \(e.localizedDescription)") }
    }

    func webExtensionController(_ controller: WKWebExtensionController, openWindowsFor extensionContext: WKWebExtensionContext) -> [any WKWebExtensionWindow] { [window] }

    func webExtensionController(_ controller: WKWebExtensionController, focusedWindowFor extensionContext: WKWebExtensionContext) -> (any WKWebExtensionWindow)? { window }

    func webExtensionController(_ controller: WKWebExtensionController, openNewTabUsing configuration: WKWebExtension.TabConfiguration, for extensionContext: WKWebExtensionContext, completionHandler: @escaping ((any WKWebExtensionTab)?, (any Error)?) -> Void) {
        log("extension asked to open \(configuration.url?.absoluteString ?? "a tab") (ignored)")
        completionHandler(nil, NSError(domain: "BouclierBench", code: 1, userInfo: [NSLocalizedDescriptionKey: "no new tabs in the benchmark"]))
    }

    func webExtensionController(_ controller: WKWebExtensionController, promptForPermissions permissions: Set<WKWebExtension.Permission>, in tab: (any WKWebExtensionTab)?, for extensionContext: WKWebExtensionContext, completionHandler: @escaping (Set<WKWebExtension.Permission>, Date?) -> Void) {
        completionHandler(permissions, nil)
    }

    func webExtensionController(_ controller: WKWebExtensionController, promptForPermissionToAccess urls: Set<URL>, in tab: (any WKWebExtensionTab)?, for extensionContext: WKWebExtensionContext, completionHandler: @escaping (Set<URL>, Date?) -> Void) {
        completionHandler(urls, nil)
    }

    func webExtensionController(_ controller: WKWebExtensionController, promptForPermissionMatchPatterns matchPatterns: Set<WKWebExtension.MatchPattern>, in tab: (any WKWebExtensionTab)?, for extensionContext: WKWebExtensionContext, completionHandler: @escaping (Set<WKWebExtension.MatchPattern>, Date?) -> Void) {
        completionHandler(matchPatterns, nil)
    }

    // MARK: web views

    func makeWebView() -> (WKWebView, NSWindow, BenchTab?) {
        let cfg = WKWebViewConfiguration()
        cfg.websiteDataStore = .nonPersistent()
        cfg.applicationNameForUserAgent = "Version/\(options.safariVersion) Safari/605.1.15"
        cfg.preferences.inactiveSchedulingPolicy = .none
        cfg.mediaTypesRequiringUserActionForPlayback = .audio
        cfg.preferences.javaScriptCanOpenWindowsAutomatically = false
        if let controller { cfg.webExtensionController = controller }
        let ucc = cfg.userContentController
        ucc.addUserScript(WKUserScript(source: captureScript, injectionTime: .atDocumentStart, forMainFrameOnly: false, in: .defaultClient))
        ucc.add(self, contentWorld: .defaultClient, name: "bench")
        if options.acceptConsent {
            ucc.addUserScript(WKUserScript(source: consentScript, injectionTime: .atDocumentEnd, forMainFrameOnly: false, in: .defaultClient))
        }
        let rect = NSRect(x: 0, y: 0, width: 1440, height: 1000)
        let webView = WKWebView(frame: rect, configuration: cfg)
        webView.uiDelegate = self
        webView.isInspectable = true
        // off-screen windows stay "visible" to WebKit, so timers, lazy loading and ads run normally
        let sel = Selector(("_setWindowOcclusionDetectionEnabled:"))
        if webView.responds(to: sel) { webView.perform(sel, with: nil) }
        slot += 1
        let win = NSWindow(contentRect: NSRect(x: -30000 - 1600 * (slot % 8), y: 200, width: 1440, height: 1000),
                           styleMask: [.borderless], backing: .buffered, defer: false)
        win.isReleasedWhenClosed = false
        win.contentView = webView
        win.orderBack(nil)
        var tab: BenchTab?
        if let controller {
            let t = BenchTab(webView: webView, window: window)
            window.tabs.append(t)
            controller.didOpenTab(t)
            controller.didActivateTab(t, previousActiveTab: nil)
            controller.didSelectTabs([t])
            tab = t
        }
        captured[ObjectIdentifier(webView)] = []
        frames[ObjectIdentifier(webView)] = []
        popups[ObjectIdentifier(webView)] = []
        return (webView, win, tab)
    }

    func close(_ webView: WKWebView, _ win: NSWindow, _ tab: BenchTab?) {
        webView.stopLoading()
        if let tab, let controller {
            controller.didCloseTab(tab, windowIsClosing: false)
            window.tabs.removeAll { $0 === tab }
        }
        webView.configuration.userContentController.removeAllScriptMessageHandlers()
        webView.configuration.userContentController.removeAllUserScripts()
        win.contentView = nil
        win.close()
        let id = ObjectIdentifier(webView)
        captured[id] = nil
        frames[id] = nil
        popups[id] = nil
    }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let wv = message.webView, let body = message.body as? [String: Any] else { return }
        let id = ObjectIdentifier(wv)
        guard captured[id] != nil else { return }
        let top = body["top"] as? Bool ?? false
        let frame = body["frame"] as? String ?? ""
        if !top { frames[id]?.insert(frame) }
        guard let res = body["res"] as? [Any] else { return }
        for item in res {
            guard let r = item as? [Any], let u = r.first as? String else { continue }
            let kind = r.count > 1 ? (r[1] as? String ?? "") : ""
            captured[id]?.append([u, kind, top ? "" : frame])
        }
    }

    // pop-up windows the page tries to open: recorded, never opened
    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        popups[ObjectIdentifier(webView)]?.append(navigationAction.request.url?.absoluteString ?? "")
        return nil
    }

    func js(_ webView: WKWebView, _ source: String) async -> Any? {
        do { return try await webView.evaluateJavaScript(source) } catch { return nil }
    }

    func sleep(_ seconds: Double) async {
        try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
    }

    // MARK: readiness

    /// Waits until the extension's network rules are active (Google's ad script is blocked).
    func waitUntilBlocking() async -> Bool {
        let test = """
        return new Promise(done => { const s = document.createElement('script');
          s.src = 'https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js?bench=' + Math.random();
          s.onload = () => done('LOADED'); s.onerror = () => done('BLOCKED'); document.head.appendChild(s);
          setTimeout(() => done('TIMEOUT'), 8000); })
        """
        for attempt in 1...24 {
            let (wv, win, tab) = makeWebView()
            wv.load(URLRequest(url: URL(string: "https://example.com/")!))
            await sleep(3)
            var verdict = "?"
            do {
                verdict = (try await wv.callAsyncJavaScript(test, contentWorld: .page) as? String) ?? "?"
            } catch { verdict = "error \(error.localizedDescription)" }
            close(wv, win, tab)
            log("readiness check \(attempt): adsbygoogle \(verdict)")
            if verdict == "BLOCKED" { return true }
            await sleep(5)
        }
        return false
    }

    // MARK: pages

    func loadSites() -> [Site] {
        guard let text = try? String(contentsOfFile: options.sites, encoding: .utf8) else { return [] }
        var list: [Site] = []
        var n = 0
        for line in text.split(separator: "\n") {
            if line.hasPrefix("#") || line.trimmingCharacters(in: .whitespaces).isEmpty { continue }
            let parts = line.split(separator: "\t", omittingEmptySubsequences: false)
            guard parts.count >= 2, let url = URL(string: String(parts[1]).trimmingCharacters(in: .whitespaces)) else { continue }
            n += 1
            if n < options.first || n > options.last { continue }
            list.append(Site(n: n, category: String(parts[0]), url: url))
        }
        return list
    }

    func measure(_ site: Site) async {
        let started = Date()
        let (wv, win, tab) = makeWebView()
        let id = ObjectIdentifier(wv)
        wv.load(URLRequest(url: site.url))
        await sleep(2)
        let deadline = Date().addingTimeInterval(options.navTimeout)
        while wv.isLoading && Date() < deadline { await sleep(0.5) }
        let loadSeconds = Date().timeIntervalSince(started)
        await sleep(options.settle)
        for fraction in ["1/3", "2/3", "0"] {
            _ = await js(wv, "window.scrollTo(0, document.documentElement.scrollHeight * \(fraction)); 0")
            await sleep(2)
        }
        await sleep(1)
        var record: [String: Any] = [:]
        let probeResult: Any?
        if options.probeWorld == "client" {
            // an isolated world sees the real DOM even when the page hooks its own DOM functions
            probeResult = try? await wv.evaluateJavaScript(probeSource, in: nil, contentWorld: .defaultClient)
        } else {
            probeResult = await js(wv, probeSource)
        }
        if let s = probeResult as? String, let d = s.data(using: .utf8),
           let obj = try? JSONSerialization.jsonObject(with: d) as? [String: Any] {
            record = obj
        } else {
            record["error"] = "no answer from the page"
            record["href"] = wv.url?.absoluteString ?? ""
        }
        record.removeValue(forKey: "res")
        let res = captured[id] ?? []
        record["res"] = res
        record["requests"] = res.count
        record["frameUrls"] = Array(frames[id] ?? []).sorted().prefix(80).map { $0 }
        record["popups"] = popups[id] ?? []
        record["n"] = site.n
        record["category"] = site.category
        record["url"] = site.url.absoluteString
        record["label"] = options.label
        record["loadSeconds"] = Int(loadSeconds.rounded())
        record["seconds"] = Int(Date().timeIntervalSince(started).rounded())
        close(wv, win, tab)
        if let data = try? JSONSerialization.data(withJSONObject: record, options: [.withoutEscapingSlashes]) {
            out?.write(data)
            out?.write("\n".data(using: .utf8)!)
        }
        log("\(site.n) \(site.url.host ?? "") \(res.count) requests, \(Int(Date().timeIntervalSince(started)))s")
    }

    func worker() async {
        while nextIndex < sites.count {
            let site = sites[nextIndex]
            nextIndex += 1
            await measure(site)
        }
    }

    func run() async {
        let activity = ProcessInfo.processInfo.beginActivity(options: [.userInitiated, .idleSystemSleepDisabled, .suddenTerminationDisabled],
                                                             reason: "Bouclier benchmark")
        defer { ProcessInfo.processInfo.endActivity(activity) }
        if let path = options.extensionPath {
            do {
                try await loadExtension(path)
            } catch {
                log("could not load the extension: \(error)")
                exit(2)
            }
            await sleep(4)
            guard await waitUntilBlocking() else {
                log("the extension never started blocking: stopping")
                exit(3)
            }
        }
        sites = loadSites()
        FileManager.default.createFile(atPath: options.out, contents: nil)
        out = FileHandle(forWritingAtPath: options.out)
        log("measuring \(sites.count) pages, \(options.parallel) at a time, label '\(options.label)'")
        await withTaskGroup(of: Void.self) { group in
            for _ in 0..<options.parallel {
                group.addTask { @MainActor in await self.worker() }
            }
        }
        try? out?.close()
        log("done")
    }
}

// MARK: - Main

let app = NSApplication.shared
app.setActivationPolicy(.accessory)
let options = Options.parse()
Task { @MainActor in
    let bench = Bench(options: options)
    await bench.run()
    exit(0)
}
app.run()

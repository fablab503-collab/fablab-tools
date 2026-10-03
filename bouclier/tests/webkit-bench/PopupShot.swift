// PopupShot — Bouclier's toolbar menu the way Safari on a Mac shows it: the Safari build of the
// extension loaded in WebKit (Safari's engine) with the website access Safari stores, a real page in
// the tab, the menu opened from the toolbar action. It clicks what it is told to, like a person would,
// and saves a picture of the page and of the menu after each step, plus one JSON line describing what the menu shows.
//
//   build/PopupShot --extension "/Applications/Bouclier.app/Contents/PlugIns/Bouclier Extension.appex/Contents/Resources" \
//       --grant every-website --site https://www.marmiton.org/ --out build/shots/popup --appearance dark \
//       [--accept] [--wait 30] [--eval 'return 1 + 1'] [--click '#site-pause .chip[data-minutes="120"]' ...]
//
// --grant   none | one-site | every-website (what Safari stores for "Always Allow on Every Website": *://*/*) | all-urls
// --accept  answer Safari's question with yes when the menu asks for access (default: Safari shows nothing)
// --wait    seconds to wait for the menu to finish after each click (a site pause makes WebKit recompile every rule)

import AppKit
import WebKit

struct Options {
    var extensionPath = "../../extension"
    var grant = "every-website"
    var site = "https://example.com/"
    var out = "build/shots/popup"
    var appearance = "dark"
    var accept = false
    var clicks: [String] = []
    var wait = 30.0
    var evals: [String] = []

    static func parse() -> Options {
        var o = Options()
        var args = Array(CommandLine.arguments.dropFirst())
        func value() -> String { args.isEmpty ? "" : args.removeFirst() }
        while !args.isEmpty {
            switch args.removeFirst() {
            case "--extension": o.extensionPath = value()
            case "--grant": o.grant = value()
            case "--site": o.site = value()
            case "--out": o.out = value()
            case "--appearance": o.appearance = value()
            case "--accept": o.accept = true
            case "--click": o.clicks.append(value())
            case "--wait": o.wait = Double(value()) ?? 30
            case "--eval": o.evals.append(value())
            case let a:
                FileHandle.standardError.write("unknown option \(a)\n".data(using: .utf8)!)
                exit(64)
            }
        }
        return o
    }
}

/// Accepts the consent pop-up the way most visitors do (same script as BouclierBench), so the page picture shows the page.
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

@MainActor final class ShotWindow: NSObject, WKWebExtensionWindow {
    var tabs: [ShotTab] = []
    func tabs(for context: WKWebExtensionContext) -> [any WKWebExtensionTab] { tabs }
    func activeTab(for context: WKWebExtensionContext) -> (any WKWebExtensionTab)? { tabs.last }
    func windowType(for context: WKWebExtensionContext) -> WKWebExtension.WindowType { .normal }
    func windowState(for context: WKWebExtensionContext) -> WKWebExtension.WindowState { .normal }
    func isPrivate(for context: WKWebExtensionContext) -> Bool { false }
    func frame(for context: WKWebExtensionContext) -> CGRect { CGRect(x: 0, y: 0, width: 1512, height: 982) }   // 14-inch MacBook Pro, default scaling
    func screenFrame(for context: WKWebExtensionContext) -> CGRect { CGRect(x: 0, y: 0, width: 1512, height: 982) }
}

@MainActor final class ShotTab: NSObject, WKWebExtensionTab {
    let webView: WKWebView
    weak var window: ShotWindow?
    init(webView: WKWebView, window: ShotWindow) { self.webView = webView; self.window = window }
    func window(for context: WKWebExtensionContext) -> (any WKWebExtensionWindow)? { window }
    func indexInWindow(for context: WKWebExtensionContext) -> Int { 0 }
    func webView(for context: WKWebExtensionContext) -> WKWebView? { webView }
    func title(for context: WKWebExtensionContext) -> String? { webView.title }
    func url(for context: WKWebExtensionContext) -> URL? { webView.url }
    func isLoadingComplete(for context: WKWebExtensionContext) -> Bool { !webView.isLoading }
    func isSelected(for context: WKWebExtensionContext) -> Bool { true }
    func size(for context: WKWebExtensionContext) -> CGSize { webView.bounds.size }
    func shouldGrantPermissionsOnUserGesture(for context: WKWebExtensionContext) -> Bool { false }
    func reload(fromOrigin: Bool, for context: WKWebExtensionContext, completionHandler: @escaping ((any Error)?) -> Void) {
        if fromOrigin { webView.reloadFromOrigin() } else { webView.reload() }
        completionHandler(nil)
    }
}

/// Off-screen windows count as hidden, and WebKit then holds CSS transitions (a switch set on by the
/// script would stay drawn off in the picture). The benchmark turns that off the same way.
@MainActor func keepDrawing(_ webView: WKWebView) {
    let sel = Selector(("_setWindowOcclusionDetectionEnabled:"))
    if webView.responds(to: sel) { webView.perform(sel, with: nil) }
}

@MainActor final class Shot: NSObject, WKWebExtensionControllerDelegate, WKNavigationDelegate {
    let options: Options
    let window = ShotWindow()
    var action: WKWebExtension.Action?
    var asked: [[String]] = []
    init(options: Options) { self.options = options }

    func webExtensionController(_ controller: WKWebExtensionController, openWindowsFor extensionContext: WKWebExtensionContext) -> [any WKWebExtensionWindow] { [window] }
    func webExtensionController(_ controller: WKWebExtensionController, focusedWindowFor extensionContext: WKWebExtensionContext) -> (any WKWebExtensionWindow)? { window }
    func webExtensionController(_ controller: WKWebExtensionController, presentActionPopup action: WKWebExtension.Action, for context: WKWebExtensionContext, completionHandler: @escaping ((any Error)?) -> Void) {
        self.action = action
        completionHandler(nil)
    }
    func webExtensionController(_ controller: WKWebExtensionController, promptForPermissions permissions: Set<WKWebExtension.Permission>, in tab: (any WKWebExtensionTab)?, for extensionContext: WKWebExtensionContext, completionHandler: @escaping (Set<WKWebExtension.Permission>, Date?) -> Void) {
        completionHandler(permissions, nil)
    }
    func webExtensionController(_ controller: WKWebExtensionController, promptForPermissionMatchPatterns matchPatterns: Set<WKWebExtension.MatchPattern>, in tab: (any WKWebExtensionTab)?, for extensionContext: WKWebExtensionContext, completionHandler: @escaping (Set<WKWebExtension.MatchPattern>, Date?) -> Void) {
        asked.append(matchPatterns.map { $0.string }.sorted())
        completionHandler(options.accept ? matchPatterns : [], nil)
    }
    func webExtensionController(_ controller: WKWebExtensionController, promptForPermissionToAccess urls: Set<URL>, in tab: (any WKWebExtensionTab)?, for extensionContext: WKWebExtensionContext, completionHandler: @escaping (Set<URL>, Date?) -> Void) {
        asked.append(urls.map { $0.absoluteString }.sorted())
        completionHandler(options.accept ? urls : [], nil)
    }

    func sleep(_ seconds: Double) async { try? await Task.sleep(nanoseconds: UInt64(seconds * 1e9)) }

    func log(_ text: String) { FileHandle.standardError.write("[PopupShot] \(text)\n".data(using: .utf8)!) }

    /// What the menu shows, as one JSON object.
    static let describeJS = """
    const $ = id => document.getElementById(id);
    const shown = el => !!el && !el.hidden && el.getClientRects().length > 0;
    return JSON.stringify({
      site: $('host') && $('host').textContent,
      count: $('count') && $('count').textContent,
      siteSwitchOn: $('site-toggle') && $('site-toggle').checked,
      accessBanner: shown($('access-banner')),
      accessText: $('access-text') ? $('access-text').textContent : null,
      allowButton: shown($('grant')),
      hideElementEnabled: $('pick') ? !$('pick').disabled : null,
      sitePauseChips: $('site-pause') ? (shown($('site-pause')) ? [...$('site-pause').querySelectorAll('.chip')].map(c => c.textContent) : []) : null,
      status: $('status') && $('status').textContent,
      busy: document.body.getAttribute('aria-busy'),
      height: document.documentElement.scrollHeight,
    });
    """

    func snapshot(_ webView: WKWebView, _ name: String) async {
        let height = (try? await webView.callAsyncJavaScript("return document.documentElement.scrollHeight", contentWorld: .page) as? Double) ?? 600
        if let win = webView.window {
            win.setContentSize(NSSize(width: 330, height: max(200, min(height, 1400))))
        }
        await sleep(0.4)
        do {
            let config = WKSnapshotConfiguration()
            let image = try await webView.takeSnapshot(configuration: config)
            guard let tiff = image.tiffRepresentation, let rep = NSBitmapImageRep(data: tiff), let png = rep.representation(using: .png, properties: [:]) else { return }
            let path = "\(options.out)-\(name).png"
            try png.write(to: URL(fileURLWithPath: path))
            log("saved \(path)")
        } catch {
            log("snapshot failed: \(error.localizedDescription)")
        }
    }

    func describe(_ webView: WKWebView, _ step: String) async {
        let json = (try? await webView.callAsyncJavaScript(Shot.describeJS, contentWorld: .page) as? String) ?? "{}"
        print("{\"step\": \"\(step)\", \"grant\": \"\(options.grant)\", \"asked\": \(asked), \"menu\": \(json)}")
        fflush(stdout)
    }

    func waitIdle(_ webView: WKWebView, seconds: Double = 20) async {
        let start = Date()
        let deadline = start.addingTimeInterval(seconds)
        while Date() < deadline {
            await sleep(0.4)
            let ready = (try? await webView.callAsyncJavaScript(
                "return typeof state !== 'undefined' && state !== null && !document.body.getAttribute('aria-busy')", contentWorld: .page) as? Bool) ?? false
            if ready {
                log(String(format: "menu ready after %.1f s", Date().timeIntervalSince(start)))
                return
            }
        }
        log("menu still busy after \(Int(seconds)) s")
    }

    func run() async {
        do {
            let ext = try await WKWebExtension(resourceBaseURL: URL(fileURLWithPath: options.extensionPath, isDirectory: true).standardizedFileURL)
            let ctx = WKWebExtensionContext(for: ext)
            ctx.uniqueIdentifier = "popupshot-\(UUID().uuidString)"
            for p in ext.requestedPermissions { ctx.setPermissionStatus(.grantedExplicitly, for: p) }
            let host = URL(string: options.site)?.host ?? "example.com"
            switch options.grant {
            case "none": break
            case "one-site": ctx.setPermissionStatus(.grantedExplicitly, for: try WKWebExtension.MatchPattern(string: "*://\(host)/*"))
            case "all-urls": ctx.setPermissionStatus(.grantedExplicitly, for: WKWebExtension.MatchPattern.allURLs())
            default: ctx.setPermissionStatus(.grantedExplicitly, for: try WKWebExtension.MatchPattern(string: "*://*/*"))
            }
            let controller = WKWebExtensionController(configuration: .nonPersistent())
            controller.delegate = self
            try controller.load(ctx)
            controller.didOpenWindow(window)
            controller.didFocusWindow(window)
            log("extension \(ext.version ?? "?") loaded, grant \(options.grant): all hosts \(ctx.hasAccessToAllHosts), all URLs \(ctx.hasAccessToAllURLs)")
            await sleep(3)   // background start and first rules

            let cfg = WKWebViewConfiguration()
            cfg.webExtensionController = controller
            cfg.applicationNameForUserAgent = "Version/27.0 Safari/605.1.15"
            cfg.userContentController.addUserScript(WKUserScript(source: consentScript, injectionTime: .atDocumentEnd, forMainFrameOnly: false, in: .defaultClient))
            let page = WKWebView(frame: NSRect(x: 0, y: 0, width: 1512, height: 900), configuration: cfg)
            keepDrawing(page)
            let pageWindow = NSWindow(contentRect: NSRect(x: -32000, y: 200, width: 1512, height: 900), styleMask: [.borderless], backing: .buffered, defer: false)
            pageWindow.isReleasedWhenClosed = false
            pageWindow.contentView = page
            pageWindow.orderBack(nil)
            let tab = ShotTab(webView: page, window: window)
            window.tabs = [tab]
            controller.didOpenTab(tab)
            controller.didActivateTab(tab, previousActiveTab: nil)
            controller.didSelectTabs([tab])
            page.load(URLRequest(url: URL(string: options.site)!))
            for _ in 0..<60 where page.isLoading { await sleep(0.5) }
            await sleep(4)
            log("page loaded: \(page.url?.absoluteString ?? "?")")
            // a picture of the top of the page as it loaded (1512 × 900, a 14-inch MacBook Pro window)
            do {
                let image = try await page.takeSnapshot(configuration: WKSnapshotConfiguration())
                if let tiff = image.tiffRepresentation, let rep = NSBitmapImageRep(data: tiff), let png = rep.representation(using: .png, properties: [:]) {
                    try png.write(to: URL(fileURLWithPath: "\(options.out)-page.png"))
                    log("saved \(options.out)-page.png")
                }
            } catch {
                log("page snapshot failed: \(error.localizedDescription)")
            }

            ctx.performAction(for: tab)
            for _ in 0..<40 where action?.popupWebView == nil { await sleep(0.25) }
            guard let menu = action?.popupWebView else { log("no menu opened"); exit(1) }
            keepDrawing(menu)
            let menuWindow = NSWindow(contentRect: NSRect(x: -30000, y: 200, width: 330, height: 700), styleMask: [.borderless], backing: .buffered, defer: false)
            menuWindow.isReleasedWhenClosed = false
            menuWindow.appearance = NSAppearance(named: options.appearance == "light" ? .aqua : .darkAqua)
            menuWindow.contentView = menu
            menuWindow.orderBack(nil)
            await waitIdle(menu)
            // a freshly loaded extension compiles all its rules first (Safari keeps them compiled);
            // wait for that, so a click measures the change itself
            let settleStart = Date()
            while Date().timeIntervalSince(settleStart) < options.wait {
                let applying = (try? await menu.callAsyncJavaScript("await refresh(); return !!state.applying", contentWorld: .page) as? Bool) ?? false
                if !applying { break }
                await sleep(2)
            }
            log(String(format: "rules ready after %.0f s", Date().timeIntervalSince(settleStart)))
            await sleep(1)
            await describe(menu, "opened")
            await snapshot(menu, "0-opened")

            // --eval: run a script in the menu (an async function body), print what it returns and how long it took
            for script in options.evals {
                let start = Date()
                let result: Any?
                do { result = try await menu.callAsyncJavaScript(script, contentWorld: .page) } catch { result = "error: \(error.localizedDescription)" }
                log(String(format: "eval took %.1f s: %@", Date().timeIntervalSince(start), String(describing: result ?? "nil")))
            }

            for (i, selector) in options.clicks.enumerated() {
                asked = []
                let found = (try? await menu.callAsyncJavaScript(
                    "const el = document.querySelector(sel); if (!el) return false; el.click(); return true;",
                    arguments: ["sel": selector], contentWorld: .page) as? Bool) ?? false
                log("clicked \(selector): \(found ? "ok" : "not found")")
                await sleep(1)
                await waitIdle(menu, seconds: options.wait)
                await sleep(1.5)
                await describe(menu, "after click \(i + 1): \(selector.replacingOccurrences(of: "\"", with: "'"))")
                await snapshot(menu, "\(i + 1)-after")
            }
        } catch {
            log("error: \(error)")
            exit(1)
        }
    }
}

let app = NSApplication.shared
app.setActivationPolicy(.accessory)
let options = Options.parse()
Task { @MainActor in
    let shot = Shot(options: options)
    await shot.run()
    exit(0)
}
app.run()

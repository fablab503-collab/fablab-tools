// PermissionProbe — asks WebKit (the engine inside Safari) how it answers Bouclier's website-access
// checks, for each access state Safari can store for an extension:
//
//   none           Safari's default ("Ask" on every website)
//   every-website  what Safari stores after "Always Allow on Every Website" or Settings → Websites →
//                  Bouclier → "When visiting other websites: Allow": the pattern *://*/*
//   one-site       "Always Allow on This Website" on one site only
//   all-urls       the manifest's own <all_urls> pattern
//
// For each state it prints permissions.contains() for <all_urls> and *://*/*, permissions.getAll(),
// then calls permissions.request() the way the popup's "Allow" button does, once as if Safari showed
// no prompt (nothing granted) and once as if the user accepted, with the patterns WebKit asked the
// browser to prompt for.
//
//   xcrun swiftc -swift-version 5 -target "$(uname -m)-apple-macos15.4" -framework WebKit \
//     -framework AppKit PermissionProbe.swift -o build/PermissionProbe
//   build/PermissionProbe "/Applications/Bouclier.app/Contents/PlugIns/Bouclier Extension.appex/Contents/Resources"

import AppKit
import WebKit

@MainActor final class Probe: NSObject, WKWebExtensionControllerDelegate, WKNavigationDelegate {
    var asked: [[String]] = []
    var accept = false
    var loaded: CheckedContinuation<Void, Never>?

    func webExtensionController(_ controller: WKWebExtensionController, promptForPermissions permissions: Set<WKWebExtension.Permission>, in tab: (any WKWebExtensionTab)?, for extensionContext: WKWebExtensionContext, completionHandler: @escaping (Set<WKWebExtension.Permission>, Date?) -> Void) {
        completionHandler(permissions, nil)
    }

    func webExtensionController(_ controller: WKWebExtensionController, promptForPermissionMatchPatterns matchPatterns: Set<WKWebExtension.MatchPattern>, in tab: (any WKWebExtensionTab)?, for extensionContext: WKWebExtensionContext, completionHandler: @escaping (Set<WKWebExtension.MatchPattern>, Date?) -> Void) {
        asked.append(matchPatterns.map { $0.string }.sorted())
        completionHandler(accept ? matchPatterns : [], nil)
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) { finish() }
    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { print("  load failed: \(error.localizedDescription)"); finish() }
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { print("  load failed: \(error.localizedDescription)"); finish() }
    private func finish() { loaded?.resume(); loaded = nil }

    static let containsJS = """
    const out = {};
    out.contains_all_urls = await browser.permissions.contains({ origins: ['<all_urls>'] });
    out.contains_every_host = await browser.permissions.contains({ origins: ['*://*/*'] });
    out.getAll_origins = (await browser.permissions.getAll()).origins;
    return JSON.stringify(out);
    """

    static let requestJS = """
    try { return String(await browser.permissions.request({ origins: [origin] })); }
    catch (e) { return 'error: ' + (e && e.message || e); }
    """

    func scenario(_ name: String, grant: [String], requests: [(String, Bool)], extensionURL: URL) async {
        print("== \(name): granted \(grant.isEmpty ? "no website" : grant.joined(separator: ", "))")
        do {
            let ext = try await WKWebExtension(resourceBaseURL: extensionURL)
            let ctx = WKWebExtensionContext(for: ext)
            ctx.uniqueIdentifier = "probe-\(name)-\(UUID().uuidString)"
            for p in ext.requestedPermissions { ctx.setPermissionStatus(.grantedExplicitly, for: p) }
            for g in grant {
                let pattern = g == "<all_urls>" ? WKWebExtension.MatchPattern.allURLs() : try WKWebExtension.MatchPattern(string: g)
                ctx.setPermissionStatus(.grantedExplicitly, for: pattern)
            }
            let controller = WKWebExtensionController(configuration: .nonPersistent())
            controller.delegate = self
            try controller.load(ctx)
            defer { try? controller.unload(ctx) }
            print("  hasAccessToAllURLs=\(ctx.hasAccessToAllURLs) hasAccessToAllHosts=\(ctx.hasAccessToAllHosts)")
            guard let cfg = ctx.webViewConfiguration else { print("  no extension web view configuration"); return }
            let webView = WKWebView(frame: NSRect(x: 0, y: 0, width: 400, height: 600), configuration: cfg)
            webView.navigationDelegate = self
            await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
                loaded = c
                webView.load(URLRequest(url: ctx.baseURL.appendingPathComponent("pages/welcome.html")))
            }
            print("  contains: \(try await webView.callAsyncJavaScript(Probe.containsJS, contentWorld: .page) ?? "nil")")
            for (origin, accepts) in requests {
                accept = accepts
                asked = []
                let r = try await webView.callAsyncJavaScript(Probe.requestJS, arguments: ["origin": origin], contentWorld: .page)
                print("  request(\(origin)) with \(accepts ? "user accepting" : "no prompt shown"): \(r ?? "nil"); WebKit asked the browser for \(asked)")
                print("  then: \(try await webView.callAsyncJavaScript(Probe.containsJS, contentWorld: .page) ?? "nil")")
            }
        } catch {
            print("  error: \(error)")
        }
    }
}

let app = NSApplication.shared
app.setActivationPolicy(.accessory)
let path = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "../../extension"
let extensionURL = URL(fileURLWithPath: path, isDirectory: true).standardizedFileURL
Task { @MainActor in
    let probe = Probe()
    // each run starts from a fresh extension context, so one answer never carries over into the next
    let runs: [(String, [String], [(String, Bool)])] = [
        ("none", [], [("<all_urls>", false)]),
        ("none", [], [("<all_urls>", true)]),
        ("none", [], [("*://*/*", false)]),
        ("none", [], [("*://*/*", true)]),
        ("one-site", ["https://example.com/*"], [("<all_urls>", false)]),
        ("one-site", ["https://example.com/*"], [("<all_urls>", true)]),
        ("every-website", ["*://*/*"], [("<all_urls>", false), ("*://*/*", false)]),
        ("all-urls", ["<all_urls>"], [("<all_urls>", false)]),
    ]
    for (name, grant, requests) in runs {
        await probe.scenario(name, grant: grant, requests: requests, extensionURL: extensionURL)
    }
    exit(0)
}
app.run()

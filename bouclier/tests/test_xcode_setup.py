#!/usr/bin/env python3
"""Unit tests for tools/xcode_setup.py (no Xcode needed): python3 tests/test_xcode_setup.py

- safari_domains: every site-limited rule, allow and block, ends up with the "domains" keys
  Safari 15-27 understand, and nothing else changes;
- patch_swift: the four changes land once on Apple's template, a second run changes nothing,
  and an unknown template is left alone (with a notice) instead of breaking the build;
- size_mac_window / edit_plists: the Mac window fits the page, and the apps declare English and French.
"""
import plistlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "tools"))
import xcode_setup  # noqa: E402

# The lines of Apple's Safari-extension template (Xcode 26/27) that patch_swift edits.
TEMPLATE = '''import WebKit

#if os(iOS)
import UIKit
typealias PlatformViewController = UIViewController
#elseif os(macOS)
import Cocoa
import SafariServices
typealias PlatformViewController = NSViewController
#endif

let extensionBundleIdentifier = "com.example.App.Extension"

class ViewController: PlatformViewController, WKNavigationDelegate, WKScriptMessageHandler {

    @IBOutlet var webView: WKWebView!

    override func viewDidLoad() {
        super.viewDidLoad()

        self.webView.navigationDelegate = self

#if os(iOS)
        self.webView.scrollView.isScrollEnabled = false
#endif

        self.webView.configuration.userContentController.add(self, name: "controller")

        self.webView.loadFileURL(Bundle.main.url(forResource: "Main", withExtension: "html")!, allowingReadAccessTo: Bundle.main.resourceURL!)
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
#if os(iOS)
        webView.evaluateJavaScript("show('ios')")
#elseif os(macOS)
        webView.evaluateJavaScript("show('mac')")
#endif
    }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
#if os(macOS)
        if (message.body as! String != "open-preferences") {
            return
        }
#endif
    }

}
'''


class SafariDomains(unittest.TestCase):
    def test_all_rule_types_rewritten(self):
        with tempfile.TemporaryDirectory() as d:
            rules_dir = Path(d) / "Shared (Extension)" / "Resources" / "rules"
            rules_dir.mkdir(parents=True)
            rules = [
                {"id": 1, "action": {"type": "block"}, "condition": {"urlFilter": "||a.test^", "initiatorDomains": ["x.test"]}},
                {"id": 2, "action": {"type": "block"}, "condition": {"urlFilter": "||b.test^", "excludedInitiatorDomains": ["y.test"]}},
                {"id": 3, "action": {"type": "allow"}, "condition": {"urlFilter": "||c.test^", "initiatorDomains": ["z.test"]}},
                {"id": 4, "action": {"type": "block"}, "condition": {"requestDomains": ["d.test"]}},
            ]
            (rules_dir / "ads.json").write_text(json.dumps(rules))
            xcode_setup.safari_domains(Path(d))
            out = json.loads((rules_dir / "ads.json").read_text())
        keys = [sorted(r["condition"]) for r in out]
        self.assertEqual(keys[0], ["domains", "urlFilter"])
        self.assertEqual(keys[1], ["excludedDomains", "urlFilter"])
        self.assertEqual(keys[2], ["domains", "urlFilter"])
        self.assertEqual(out[3], rules[3])
        self.assertEqual(out[0]["condition"]["domains"], ["x.test"])
        self.assertFalse(any("initiatorDomains" in r["condition"] or "excludedInitiatorDomains" in r["condition"] for r in out))


class PatchSwift(unittest.TestCase):
    def run_patch(self, text):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "Shared (App)" / "ViewController.swift"
            path.parent.mkdir(parents=True)
            path.write_text(text)
            xcode_setup.patch_swift(Path(d))
            once = path.read_text()
            xcode_setup.patch_swift(Path(d))
            return once, path.read_text()

    def test_template_patched_once(self):
        once, twice = self.run_patch(TEMPLATE)
        self.assertEqual(once, twice, "a second run must change nothing")
        self.assertIn("#if os(iOS)\nimport UIKit\nimport SafariServices\n", once)
        self.assertIn("isScrollEnabled = true", once)
        self.assertNotIn("isScrollEnabled = false", once)
        self.assertIn("if #available(iOS 26.2, *) {\n            webView.evaluateJavaScript(\"show('ios', undefined, true, true)\")", once)
        self.assertIn("SFSafariSettings.openExtensionsSettings(forIdentifiers: [extensionBundleIdentifier])", once)
        self.assertEqual(once.count("SFSafariSettings"), 1)
        self.assertEqual(once.count("decidePolicyFor navigationAction"), 1)
        self.assertIn("decisionHandler(.cancel)", once)
        # the navigation method comes before userContentController, which keeps its iOS branch
        self.assertLess(once.index("decidePolicyFor"), once.index("func userContentController"))
        self.assertIn("didReceive message: WKScriptMessage) {\n#if os(iOS)\n        if #available(iOS 26.2, *)", once)
        # the macOS code is untouched
        self.assertIn('if (message.body as! String != "open-preferences") {', once)

    def test_unknown_template_left_alone(self):
        other = "import SwiftUI\nstruct ContentView {}\n"
        once, twice = self.run_patch(other)
        self.assertEqual(once, other)
        self.assertEqual(twice, other)


STORYBOARD = """<document><scenes><scene><objects>
<window key="window" title="Bouclier" id="IQv-IB-iLA">
<rect key="contentRect" x="196" y="240" width="425" height="325"/>
<rect key="screenRect" x="0.0" y="0.0" width="1680" height="1027"/>
</window></objects></scene><scene><objects><viewController id="XfG-lQ-9wD"><view key="view" id="m2S-Jp-Qdl">
<rect key="frame" x="0.0" y="0.0" width="425" height="325"/>
<subviews><wkWebView id="eOr-cG-IQY"><rect key="frame" x="0.0" y="0.0" width="425" height="325"/></wkWebView></subviews>
</view></viewController></objects></scene></scenes></document>"""


class WindowAndLanguages(unittest.TestCase):
    def test_mac_window_sized_once_and_ios_untouched(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            mac = root / "macOS (App)" / "Base.lproj" / "Main.storyboard"
            ios = root / "iOS (App)" / "Base.lproj" / "Main.storyboard"
            for b in (mac, ios):
                b.parent.mkdir(parents=True)
                b.write_text(STORYBOARD)
            xcode_setup.size_mac_window(root)
            text = mac.read_text()
            w, h = xcode_setup.MAC_WINDOW
            self.assertEqual(text.count(f'width="{w}" height="{h}"'), 3)
            self.assertIn('width="1680" height="1027"', text)          # the screen rect is not a frame to resize
            self.assertEqual(ios.read_text(), STORYBOARD)
            xcode_setup.size_mac_window(root)                          # a second run changes nothing
            self.assertEqual(mac.read_text(), text)

    def test_apps_and_extensions_declare_english_and_french(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            for folder, extra in (("iOS (App)", {}), ("macOS (Extension)", {"NSExtension": {}})):
                path = root / folder / "Info.plist"
                path.parent.mkdir(parents=True)
                with open(path, "wb") as f:
                    plistlib.dump(dict(extra), f)
            xcode_setup.edit_plists(root)
            for folder in ("iOS (App)", "macOS (Extension)"):
                with open(root / folder / "Info.plist", "rb") as f:
                    info = plistlib.load(f)
                self.assertEqual(info["CFBundleLocalizations"], ["en", "fr"])
                self.assertEqual(info["CFBundleDevelopmentRegion"], "en")

if __name__ == "__main__":
    unittest.main(verbosity=2)

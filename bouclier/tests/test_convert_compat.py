"""tools/convert.py: the rulesets for Safari before 26 (compat_rules, webkit_count_legacy).

python3 -m unittest tests/test_convert_compat.py
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "tools"))
import convert  # noqa: E402

BLOCK = {"type": "block"}
ALLOW = {"type": "allow"}


class CompatRules(unittest.TestCase):
    def test_domains_become_one_rule_each_with_types_but_frames(self):
        rules = [
            {"id": 1, "priority": 2, "action": ALLOW, "condition": {"urlFilter": "||ok.test/ads.js"}},
            {"id": 2, "priority": 1, "action": BLOCK, "condition": {"requestDomains": ["a.test", "b.test"], "domainType": "thirdParty"}},
            {"id": 3, "priority": 1, "action": BLOCK, "condition": {"urlFilter": "||c.test/banner/", "resourceTypes": ["image"]}},
            {"id": 4, "priority": 1, "action": BLOCK, "condition": {"urlFilter": "/adframe.", "excludedResourceTypes": ["script"]}},
        ]
        out = convert.compat_rules(rules)
        self.assertEqual([r["id"] for r in out], [1, 2, 3, 4, 5])
        self.assertEqual(out[0]["action"], ALLOW)
        self.assertNotIn("resourceTypes", out[0]["condition"], "an exception keeps covering everything")
        a, b = out[1]["condition"], out[2]["condition"]
        self.assertEqual((a["urlFilter"], b["urlFilter"]), ("||a.test^", "||b.test^"))
        self.assertEqual(a["domainType"], "thirdParty")
        self.assertEqual(a["resourceTypes"], convert.COMPAT_TYPES)
        self.assertNotIn("sub_frame", a["resourceTypes"])
        self.assertNotIn("main_frame", a["resourceTypes"])
        self.assertEqual(out[3]["condition"]["resourceTypes"], ["image"])
        self.assertEqual(out[4]["condition"], {"urlFilter": "/adframe.", "excludedResourceTypes": ["script"]})
        self.assertFalse(any("requestDomains" in r["condition"] for r in out))
        self.assertIn("requestDomains", rules[1]["condition"], "the regular rules are left as they were")

    def test_legacy_count(self):
        count = convert.webkit_count_legacy
        self.assertEqual(count({"action": BLOCK, "condition": {"requestDomains": ["a.test", "b.test"]}}), 0)
        self.assertEqual(count({"action": BLOCK, "condition": {"urlFilter": "||a.test^"}}), 2)
        self.assertEqual(count({"action": BLOCK, "condition": {"urlFilter": "||a.test^", "resourceTypes": convert.COMPAT_TYPES}}), 1)
        self.assertEqual(count({"action": BLOCK, "condition": {"urlFilter": "||a.test^", "resourceTypes": ["sub_frame"]}}), 1)
        self.assertEqual(count({"action": {"type": "allowAllRequests"}, "condition": {"urlFilter": "||a.test^", "resourceTypes": ["main_frame", "sub_frame"]}}), 1)
        self.assertEqual(count({"action": BLOCK, "condition": {"urlFilter": "||a.test^", "resourceTypes": list(convert.SAFARI_TYPES)}}), 1)

    def test_frames_come_back_for_ad_frame_hosts(self):
        import tempfile
        with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as f:
            f.write("! comment\ngoogleads.g.doubleclick.net  ! 23 frames\n\nsafeframe.googlesyndication.com\n")
        frames = convert.frame_domains(f.name)
        os.unlink(f.name)
        self.assertIn("doubleclick.net", frames)
        self.assertIn("g.doubleclick.net", frames)
        self.assertNotIn("net", frames, "never a whole top-level domain")
        self.assertEqual(convert.frame_domains("/nonexistent/frame-hosts.txt"), frozenset())
        rules = [
            {"id": 1, "priority": 2, "action": ALLOW, "condition": {"urlFilter": "||doubleclick.net/ok/"}},
            {"id": 2, "priority": 1, "action": BLOCK, "condition": {"requestDomains": ["doubleclick.net", "other.test"], "domainType": "thirdParty"}},
            {"id": 3, "priority": 1, "action": BLOCK, "condition": {"urlFilter": "||googlesyndication.com^", "initiatorDomains": ["news.test"]}},
            {"id": 4, "priority": 1, "action": BLOCK, "condition": {"urlFilter": "||doubleclick.net/pixel", "resourceTypes": ["image"]}},
            {"id": 5, "priority": 1, "action": BLOCK, "condition": {"urlFilter": "||doubleclick.net/ads/"}},
        ]
        out = convert.compat_rules(rules, frames)
        frame_only = [r["condition"] for r in out if r["condition"].get("resourceTypes") == ["sub_frame"]]
        self.assertEqual(frame_only, [
            {"urlFilter": "||doubleclick.net^", "domainType": "thirdParty", "resourceTypes": ["sub_frame"]},
            {"urlFilter": "||googlesyndication.com^", "initiatorDomains": ["news.test"], "resourceTypes": ["sub_frame"]},
        ], "a host the lists block and that served ad frames, with the rule's own conditions; no path rules")
        self.assertEqual(len(out), len(convert.compat_rules(rules)) + 2)
        self.assertEqual(out[0]["action"], ALLOW, "exceptions still come first")
        self.assertEqual(sum(convert.webkit_count_legacy({"action": BLOCK, "condition": c}) for c in frame_only), 2)
        doubleclick = [r["condition"] for r in out if r["condition"].get("urlFilter") == "||doubleclick.net^"]
        self.assertEqual(sorted(len(c["resourceTypes"]) for c in doubleclick), [1, len(convert.COMPAT_TYPES)])

    def test_compat_fits_where_the_unlimited_version_did_not(self):
        hosts = [f"h{i}.test" for i in range(1000)]
        rules = [{"id": 1, "priority": 1, "action": BLOCK, "condition": {"requestDomains": hosts}}]
        out = convert.compat_rules(rules)
        self.assertEqual(len(out), 1000)
        self.assertEqual(sum(convert.webkit_count_legacy(r) for r in out), 1000)
        self.assertEqual(sum(convert.webkit_count(r) for r in rules), 1000, "the regular rule costs the same on Safari 26")


if __name__ == "__main__":
    unittest.main()

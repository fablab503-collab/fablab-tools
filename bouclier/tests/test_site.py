#!/usr/bin/env python3
"""Checks the official page (site/, published at fablab503-collab.github.io/bouclier): every local
link and image exists, images say what they show and how big they are, both languages are there,
French typography in the French text, and nothing private is published.

usage: python3 tests/test_site.py
"""
import html.parser
import re
import unittest
from pathlib import Path

SITE = Path(__file__).resolve().parent.parent / "site"
PAGES = ["index.html", "support.html", "privacy.html"]


class Page(html.parser.HTMLParser):
    def __init__(self):
        super().__init__()
        self.links, self.images, self.ids = [], [], set()
        self.en = self.fr = 0

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if "id" in a:
            self.ids.add(a["id"])
        classes = (a.get("class") or "").split()
        self.en += "en" in classes
        self.fr += "fr" in classes
        if tag == "a" and a.get("href"):
            self.links.append(a["href"])
        if tag == "img":
            self.images.append(a)


def parse(name):
    p = Page()
    p.feed((SITE / name).read_text(encoding="utf-8"))
    return p


class OfficialPage(unittest.TestCase):
    def test_local_links_and_images_exist(self):
        for name in PAGES:
            page = parse(name)
            for href in page.links:
                if re.match(r"^(https?:|mailto:)", href):
                    continue
                path, _, anchor = href.partition("#")
                target = SITE / (path or name)
                self.assertTrue(target.exists(), f"{name}: link to {href}")
                if anchor and path.endswith(".html"):
                    self.assertIn(anchor, parse(path).ids, f"{name}: no #{anchor} in {path}")
            for img in page.images:
                self.assertTrue((SITE / img["src"]).exists(), f"{name}: image {img['src']}")

    def test_images_have_size_and_description(self):
        for name in PAGES:
            for img in parse(name).images:
                self.assertIn("width", img, img["src"])
                self.assertIn("height", img, img["src"])
                self.assertIn("alt", img, img["src"])

    def test_both_languages(self):
        page = parse("index.html")
        self.assertGreater(page.en, 20)
        self.assertEqual(page.en, page.fr, "index.html: an English text without its French one, or the reverse")
        for lang in ("en", "fr"):
            self.assertTrue((SITE / "img" / f"mac-{lang}.webp").exists())
            self.assertTrue((SITE / "img" / f"ipad-iphone-{lang}.webp").exists())

    def test_french_typography(self):
        text = (SITE / "index.html").read_text(encoding="utf-8")
        for block in re.findall(r'<(?:span|p|ul|ol) class="fr"[^>]*>(.*?)</(?:span|p|ul|ol)>', text, re.S):
            words = re.sub(r"<[^>]+>", "", block)
            self.assertNotIn("'", words, f"straight apostrophe in: {words[:80]}")
            self.assertIsNone(re.search(r" [:;?!]", words), f"breaking space before punctuation in: {words[:80]}")

    def test_nothing_private(self):
        for path in SITE.rglob("*"):
            if path.suffix not in (".html", ".md", ".txt"):
                continue
            text = path.read_text(encoding="utf-8")
            for private in ("/Volumes/", "SecondBrain", "/Users/", "+33", "localhost", "127.0.0.1"):
                self.assertNotIn(private, text, f"{path.name} mentions {private}")


if __name__ == "__main__":
    unittest.main(verbosity=2)

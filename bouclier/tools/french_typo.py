#!/usr/bin/env python3
"""French typography for the official page (site/*.html): in French text only (elements with
class "fr" or lang="fr", and the alt text of French images), the typographic apostrophe and a
non-breaking space before : ; ? ! and inside « ». Code, URLs and markup are left alone.

usage: tools/french_typo.py site/index.html site/support.html   (edits in place, prints the count)
"""
import html.parser
import re
import sys

NBSP = " "


def french(text):
    text = text.replace("'", "’")
    text = re.sub(r"[  ]*([:;?!])(?=\s|$|<|\)|»)", lambda m: NBSP + m.group(1), text)
    text = re.sub(r"«[  ]*", "«" + NBSP, text)
    text = re.sub(r"[  ]*»", NBSP + "»", text)
    return text


class Walker(html.parser.HTMLParser):
    """Rebuilds the page piece by piece, changing only French text."""
    VOID = {"meta", "link", "img", "br", "hr", "input", "source"}

    def __init__(self):
        super().__init__(convert_charrefs=False)
        self.out, self.stack = [], []   # stack of (tag, is_french)

    def _fr(self):
        return any(f for _, f in self.stack) and not any(t in ("code", "script", "style") for t, _ in self.stack)

    def handle_starttag(self, tag, attrs):
        raw = self.get_starttag_text()
        a = dict(attrs)
        is_fr = "fr" in (a.get("class") or "").split() or a.get("lang") == "fr"
        # "Français" on the language button is the English page's link to French: the text is fine either way
        if tag == "img" and is_fr and a.get("alt"):
            raw = raw.replace(f'alt="{a["alt"]}"', f'alt="{french(a["alt"])}"')
        self.out.append(raw)
        if tag not in self.VOID:
            self.stack.append((tag, is_fr))

    def handle_startendtag(self, tag, attrs):
        self.out.append(self.get_starttag_text())

    def handle_endtag(self, tag):
        self.out.append(f"</{tag}>")
        for i in range(len(self.stack) - 1, -1, -1):
            if self.stack[i][0] == tag:
                del self.stack[i:]
                break

    def handle_data(self, data):
        self.out.append(french(data) if self._fr() else data)

    def handle_entityref(self, name):
        self.out.append(f"&{name};")

    def handle_charref(self, name):
        self.out.append(f"&#{name};")

    def handle_comment(self, data):
        self.out.append(f"<!--{data}-->")

    def handle_decl(self, decl):
        self.out.append(f"<!{decl}>")


for path in sys.argv[1:]:
    src = open(path, encoding="utf-8").read()
    w = Walker()
    w.feed(src)
    w.close()
    out = "".join(w.out)
    changed = sum(1 for a, b in zip(src, out) if a != b) + abs(len(src) - len(out))
    open(path, "w", encoding="utf-8").write(out)
    print(f"{path}: {changed} characters changed")

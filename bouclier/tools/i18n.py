#!/usr/bin/env python3
"""Builds the extension's _locales from extension/i18n/*.json and checks them.

Each extension/i18n/<page>.json holds one entry per text, English and French side by side:

    { "popup_pause_for": { "en": "Pause on this site for", "fr": "Mettre en pause sur ce site pendant" } }

This writes extension/_locales/en/messages.json (the default language) and
extension/_locales/fr/messages.json, and fails when a text is missing in one language, when the {0}
{1}… slots or the HTML tags differ between the two, when a key is used in the code but defined
nowhere, or when a text holds a "$" (browsers read "$" in messages as a placeholder).

    python3 tools/i18n.py            # build and check
    python3 tools/i18n.py --check    # check only (exit 1 when the files on disk are out of date)
"""
import argparse
import collections
import glob
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EXT = os.path.join(ROOT, "extension")
LANGS = ("en", "fr")
KEY = re.compile(r"^[a-z0-9_]+$")
SLOT = re.compile(r"\{(\d)\}")
TAG = re.compile(r"</?([a-z][a-z0-9]*)\b", re.I)
# where the code names a text: t('key'), data-i18n="key", data-i18n-html="key", attr lists, __MSG_key__
USES = [
    re.compile(r"""\bt\(\s*['"]([a-z0-9_]+)['"]"""),
    re.compile(r"""data-i18n(?:-html)?=["']([a-z0-9_]+)["']"""),
    re.compile(r"""data-i18n-attr=["']([^"']+)["']"""),
    re.compile(r"__MSG_([a-z0-9_]+)__"),
]


def load_sources():
    entries, errors = {}, []
    for path in sorted(glob.glob(os.path.join(EXT, "i18n", "*.json"))):
        name = os.path.basename(path)
        try:
            data = json.load(open(path, encoding="utf-8"))
        except ValueError as e:
            errors.append(f"{name}: not valid JSON ({e})")
            continue
        for key, texts in data.items():
            where = f"{name}: {key}"
            if not KEY.match(key):
                errors.append(f"{where}: keys are lowercase letters, digits and _")
                continue
            if key in entries:
                errors.append(f"{where}: already defined in {entries[key]['file']}")
                continue
            if not isinstance(texts, dict) or any(not isinstance(texts.get(l), str) or not texts.get(l).strip() for l in LANGS):
                errors.append(f"{where}: needs a text for {' and '.join(LANGS)}")
                continue
            en, fr = texts["en"], texts["fr"]
            if sorted(SLOT.findall(en)) != sorted(SLOT.findall(fr)):
                errors.append(f"{where}: the {{n}} slots differ between English and French")
            if collections.Counter(t.lower() for t in TAG.findall(en)) != collections.Counter(t.lower() for t in TAG.findall(fr)):
                errors.append(f"{where}: the HTML tags differ between English and French")
            if "$" in en or "$" in fr:
                errors.append(f"{where}: no $ in texts (browsers read it as a placeholder)")
            entries[key] = {"file": name, "en": en, "fr": fr}
    return entries, errors


def code_files():
    files = glob.glob(os.path.join(EXT, "**", "*.js"), recursive=True) + glob.glob(os.path.join(EXT, "**", "*.html"), recursive=True)
    files.append(os.path.join(EXT, "manifest.json"))
    # i18n.js only explains t() in its comments
    return [p for p in files if not any(x in p for x in ("/_locales/", "/rules/", "/cosmetic/", "/common/i18n.js"))]


def used_keys():
    keys = collections.defaultdict(set)
    for path in code_files():
        text = open(path, encoding="utf-8", errors="replace").read()
        rel = os.path.relpath(path, ROOT)
        for pattern in USES:
            for m in pattern.finditer(text):
                if pattern.pattern.startswith("data-i18n-attr"):
                    for pair in m.group(1).split(","):
                        if ":" in pair:
                            keys[pair.split(":", 1)[1].strip()].add(rel)
                else:
                    keys[m.group(1)].add(rel)
    return keys


def render(entries, lang):
    out = {}
    for key in sorted(entries):
        out[key] = {"message": entries[key][lang], "description": entries[key]["file"]}
    return json.dumps(out, ensure_ascii=False, indent=1, sort_keys=True) + "\n"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--check", action="store_true", help="only check; fail when _locales is out of date")
    args = ap.parse_args()
    entries, errors = load_sources()
    # App Store Connect refuses the upload when the extension's description is over 112 characters
    # in any language (it reads every _locales/<lang>/messages.json)
    manifest = json.load(open(os.path.join(EXT, "manifest.json"), encoding="utf-8"))
    for field in ("description", "name", "short_name"):
        m = re.fullmatch(r"__MSG_(\w+)__", str(manifest.get(field, "")))
        limit = 112 if field == "description" else 45
        if m and m.group(1).lower() in entries:
            for lang in LANGS:
                text = entries[m.group(1).lower()][lang]
                if len(text) > limit:
                    errors.append(f"{m.group(1)} ({lang}): the manifest's {field} is {len(text)} characters, App Store Connect takes {limit} at most")
    used = used_keys()
    for key in sorted(used):
        if key not in entries:
            errors.append(f"{key}: used in {', '.join(sorted(used[key]))} but defined in no extension/i18n/*.json")
    # a key named anywhere in the code (for example chosen with a ternary) counts as used
    all_code = "\n".join(open(p, encoding="utf-8", errors="replace").read() for p in code_files())
    unused = sorted(k for k in entries if k not in used and k not in all_code and not k.startswith(("list_", "platform_device_")))
    for lang in LANGS:
        path = os.path.join(EXT, "_locales", lang, "messages.json")
        text = render(entries, lang)
        current = open(path, encoding="utf-8").read() if os.path.exists(path) else None
        if args.check:
            if current != text:
                errors.append(f"{os.path.relpath(path, ROOT)} is out of date: run python3 tools/i18n.py")
        elif current != text:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            open(path, "w", encoding="utf-8").write(text)
    for e in errors:
        print("  ! " + e, file=sys.stderr)
    if unused:
        print(f"  (not used anywhere yet: {', '.join(unused[:12])}{' …' if len(unused) > 12 else ''})", file=sys.stderr)
    print(f"{len(entries)} texts in {' and '.join(LANGS)}; {len(errors)} problem(s)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())

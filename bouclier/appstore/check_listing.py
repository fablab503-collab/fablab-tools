#!/usr/bin/env python3
"""Checks appstore/listing.json against App Store Connect's length limits."""
import json
import sys
from pathlib import Path

LIMITS = {"name": 30, "subtitle": 30, "promotionalText": 170, "keywords": 100, "description": 4000, "whatsNew": 4000}
data = json.loads((Path(__file__).parent / "listing.json").read_text())
bad = 0
for locale, fields in data["locales"].items():
    for key, limit in LIMITS.items():
        n = len(fields.get(key, ""))
        ok = n <= limit and (n > 0 or key == "whatsNew")
        bad += not ok
        print(f"{'ok ' if ok else 'BAD'} {locale:6} {key:16} {n:5} / {limit}")
    words = [w.strip() for w in fields["keywords"].split(",")]
    dupes = [w for w in words if w.lower() in fields["name"].lower()]
    if dupes:
        print(f"    note: keywords already in the name (wasted): {dupes}")
n = len(data["reviewNotes"])
bad += n > 4000
print(f"{'ok ' if n <= 4000 else 'BAD'} review notes {n} / 4000")
sys.exit(1 if bad else 0)

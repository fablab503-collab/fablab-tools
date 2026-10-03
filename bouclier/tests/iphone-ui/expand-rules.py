#!/usr/bin/env python3
"""Experiment (3 Oct 2026): writes every requestDomains rule of Bouclier's rule files out as one
urlFilter rule per host ("||host^"), the other conditions unchanged, allow rules still first.

Safari turns each host of a requestDomains list into one WebKit rule anyway, so the number of WebKit
rules stays the same; only the way the rules are written changes. Used by expanded-app.sh to see
whether Safari on iOS 18 skips requestDomains rules.

usage: expand-rules.py <rules folder>   (rewrites the .json files in place)
"""
import glob
import json
import os
import sys


def expand(rules):
    out = []
    for r in rules:
        c = r["condition"]
        hosts = c.get("requestDomains")
        if hosts and "urlFilter" not in c and "regexFilter" not in c:
            base = {k: v for k, v in c.items() if k != "requestDomains"}
            for h in hosts:
                cond = dict(base)
                cond["urlFilter"] = "||" + h + "^"
                out.append({"priority": r["priority"], "action": r["action"], "condition": cond})
        else:
            out.append({"priority": r["priority"], "action": r["action"], "condition": c})
    return [{"id": i, **r} for i, r in enumerate(out, 1)]


if __name__ == "__main__":
    folder = sys.argv[1]
    before = after = 0
    for path in sorted(glob.glob(os.path.join(folder, "*.json"))):
        with open(path, encoding="utf-8") as f:
            rules = json.load(f)
        new = expand(rules)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(new, f, separators=(",", ":"), ensure_ascii=False)
        print(f"{os.path.basename(path):18} {len(rules):6} -> {len(new):6} rules")
        before += len(rules)
        after += len(new)
    print(f"{'all':18} {before:6} -> {after:6} rules")

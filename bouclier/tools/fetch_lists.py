#!/usr/bin/env python3
"""Download the filter lists named in lists.json into a cache folder.

Each list has several URLs (official host first, GitHub mirror second). The
first one that answers with something that looks like a filter list wins. If
every URL fails, the previously cached copy is kept, so a flaky network never
breaks a build. Standard library only.
"""
import argparse
import json
import os
import sys
import time
import urllib.request

UA = "Bouclier/1.0 (+https://github.com/; filter list updater)"


def looks_like_list(data: bytes) -> bool:
    head = data[:4000].decode("utf-8", "replace")
    lines = [l for l in data.decode("utf-8", "replace").splitlines() if l.strip()]
    return len(lines) > 50 and ("[Adblock" in head or head.lstrip().startswith("!") or "||" in head or "##" in head
                                 or all(" " not in l for l in lines[:20] if not l.startswith(("!", "#"))))


def fetch(url, timeout=45):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=timeout) as res:
        return res.read()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--lists", default=os.path.join(os.path.dirname(__file__), "lists.json"))
    ap.add_argument("--cache", required=True)
    ap.add_argument("--only", nargs="*")
    args = ap.parse_args()
    os.makedirs(args.cache, exist_ok=True)
    lists = json.load(open(args.lists, encoding="utf-8"))["lists"]
    failures = 0
    for cfg in lists:
        if args.only and cfg["id"] not in args.only:
            continue
        if not cfg.get("urls"):
            continue  # built into Bouclier, nothing to download
        dest = os.path.join(args.cache, cfg["id"] + ".txt")
        ok = False
        for url in cfg["urls"]:
            try:
                t0 = time.time()
                data = fetch(url)
                if not looks_like_list(data):
                    print(f"  {cfg['id']}: {url} did not look like a filter list", file=sys.stderr)
                    continue
                tmp = dest + ".part"
                with open(tmp, "wb") as f:
                    f.write(data)
                os.replace(tmp, dest)
                print(f"  {cfg['id']:<12} {len(data)/1024:8.0f} KB  {time.time()-t0:4.1f}s  {url}")
                ok = True
                break
            except Exception as e:  # noqa: BLE001 - report and try the next mirror
                print(f"  {cfg['id']}: {url} failed ({e})", file=sys.stderr)
        if not ok:
            failures += 1
            if os.path.exists(dest):
                print(f"  {cfg['id']}: keeping the copy from {time.ctime(os.path.getmtime(dest))}", file=sys.stderr)
            else:
                print(f"  {cfg['id']}: no copy at all, this list will be missing", file=sys.stderr)
    sys.exit(0 if failures < len([l for l in lists if l.get("urls")]) else 1)


if __name__ == "__main__":
    main()

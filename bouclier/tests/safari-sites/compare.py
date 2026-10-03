#!/usr/bin/env python3
"""Before/after comparison of two Safari site runs (run.sh), with an independent yardstick.

usage: compare.py results/<before>.jsonl results/<after>.jsonl [more runs...]

What is counted, on the pages that answered in every run (the ad-block test pages are left out):
  * trackers through: third-party requests that loaded and belong to a company Disconnect's public
    tracker list files under Advertising, Analytics or Fingerprinting (Disconnect is used only as a
    measuring stick here, it is not part of Bouclier's lists; downloaded once to results/)
  * ad networks through: the same with analyze.py's short list of well-known ad and tracking networks
  * visible ad spaces and "turn off your ad blocker" messages, as probe.js saw them
Blocked requests never start, so they are not in a page's list: fewer is better everywhere.
"""
import json
import sys
import urllib.request
from collections import Counter
from pathlib import Path
from urllib.parse import urlsplit

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from analyze import AD_NETWORKS, site_of, under  # noqa: E402

DISCONNECT_URL = "https://raw.githubusercontent.com/disconnectme/disconnect-tracking-protection/master/services.json"
TRACKING = {"Advertising", "Analytics", "FingerprintingInvasive", "FingerprintingGeneral", "Cryptomining"}
TEST_PAGES = ("adblock.turtlecute.org", "canyoublockit.com", "adblock-tester.com")


def disconnect_domains():
    cache = HERE / "results" / "disconnect-services.json"
    if not cache.exists():
        cache.write_bytes(urllib.request.urlopen(DISCONNECT_URL, timeout=60).read())
    data = json.loads(cache.read_text())
    out = set()
    for cat, entries in data["categories"].items():
        if cat not in TRACKING:
            continue
        for entry in entries:
            for _org, urls in entry.items():
                for _site, domains in urls.items():
                    if isinstance(domains, list):
                        out.update(d.lower() for d in domains)
    return out


def summarise(path, trackers, keep):
    pages = {}
    for line in Path(path).read_text().splitlines():
        if line.strip():
            p = json.loads(line)
            pages[p["url"]] = p
    s = Counter()
    by_tracker = Counter()
    for url in keep:
        p = pages[url]
        page_host = (urlsplit(p["href"]).hostname or "").lower()
        s["pages"] += 1
        s["requests"] += p.get("requests", 0)
        s["ad spaces visible"] += len(p.get("adVisible", []))
        s["ad-blocker messages"] += 1 if (p.get("adblockWall") or p.get("adblockText")) else 0
        for res_url, _initiator in p.get("res", []):
            if not res_url.startswith("http"):
                continue
            host = (urlsplit(res_url).hostname or "").lower()
            if site_of(host) == site_of(page_host):
                continue
            if under(host, trackers):
                s["trackers through"] += 1
                by_tracker[site_of(host)] += 1
            if under(host, AD_NETWORKS):
                s["ad networks through"] += 1
    return s, by_tracker


def main():
    runs = sys.argv[1:]
    if len(runs) < 2:
        sys.exit(__doc__)
    trackers = disconnect_domains()
    answered = []
    for path in runs:
        ok = set()
        for line in Path(path).read_text().splitlines():
            if line.strip():
                p = json.loads(line)
                if "error" not in p and not any(t in p["url"] for t in TEST_PAGES):
                    ok.add(p["url"])
        answered.append(ok)
    keep = sorted(set.intersection(*answered))
    results = [summarise(p, trackers, keep) for p in runs]
    names = [Path(p).stem for p in runs]
    print(f"Pages that answered in every run: {len(keep)}\n")
    print(f"{'':24s}" + "".join(f"{n[:18]:>20s}" for n in names))
    for key in ("requests", "trackers through", "ad networks through", "ad spaces visible", "ad-blocker messages"):
        print(f"{key:24s}" + "".join(f"{r[0][key]:>20d}" for r in results))
    first, last = results[0][0]["trackers through"], results[-1][0]["trackers through"]
    if first:
        print(f"\nTrackers through: {first} -> {last} ({(last - first) / first * 100:+.1f}%)")
    print("\nStill through in the last run:", ", ".join(f"{d} {n}" for d, n in results[-1][1].most_common(15)) or "nothing")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Compares benchmark runs of BouclierBench (results/<label>.jsonl): how many ad and tracker
requests got through, how many ad spaces stayed visible, how many pages broke.

usage: compare.py [--ref DIR] [--base LABEL] [--md OUT.md] results/none-train.jsonl results/v120-train.jsonl ...

What counts as an ad or tracker request is decided by lists that Bouclier does NOT ship, so the
judge is independent of the rules being judged:
  * adguard: the AdGuard DNS filter (AdGuard's ad server + tracker domains, plus EasyList/EasyPrivacy
    domains), with its own exceptions;
  * hagezi:  HaGeZi's Multi PRO domain list (ads, trackers, telemetry);
  * all:     adguard + recovery (the headline number);
  * major:   60 big ad and tracking networks (Google ads, Amazon, Criteo, Taboola...);
  * recovery: ad-recovery services that re-insert ads once a blocker is detected (Ad-Shield), which
    the DNS lists leave out.
Only third-party requests count (a site's own files are never "trackers" here). The reference lists
are downloaded into --ref (default: ./ref) by fetch_refs.sh; they are GPL-3.0 and only used here.

A page counts when it answered in every compared run. "Blocked" percentages are relative to the run
without an ad blocker (label starting with "none"), "fewer" percentages relative to --base.
"""
import argparse
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path
from urllib.parse import urlsplit

HERE = Path(__file__).resolve().parent

MAJOR = """doubleclick.net googlesyndication.com googleadservices.com google-analytics.com googletagservices.com
googletagmanager.com adservice.google.com amazon-adsystem.com adnxs.com criteo.com criteo.net taboola.com
outbrain.com rubiconproject.com pubmatic.com openx.net casalemedia.com smartadserver.com teads.tv adsrvr.org
scorecardresearch.com quantserve.com moatads.com hotjar.com 3lift.com adform.net bidswitch.net mathtag.com
yieldmo.com sharethrough.com seedtag.com indexww.com lijit.com sonobi.com adsafeprotected.com doubleverify.com
krxd.net demdex.net everesttech.net omtrdc.net 2mdn.net adsymptotic.com media.net contextweb.com 33across.com
gumgum.com rlcdn.com bluekai.com chartbeat.com chartbeat.net xiti.com ati-host.net quantcount.com permutive.com
permutive.app sddan.com facebook.net connect.facebook.net ads-twitter.com analytics.tiktok.com""".split()


AD_RECOVERY = """html-load.com html-load.cc content-loader.com css-load.com img-load.com 07c225f3.online""".split()


class Suffixes:
    """Registrable domain (eTLD+1) with the Public Suffix List."""

    def __init__(self, path):
        self.rules, self.exceptions, self.wild = set(), set(), set()
        for line in Path(path).read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("//"):
                continue
            if line.startswith("!"):
                self.exceptions.add(line[1:])
            elif line.startswith("*."):
                self.wild.add(line[2:])
            else:
                self.rules.add(line)
        self.cache = {}

    def site(self, host):
        host = (host or "").lower().strip(".")
        if host in self.cache:
            return self.cache[host]
        parts = host.split(".")
        best = 1
        for i in range(len(parts)):
            cand = ".".join(parts[i:])
            if cand in self.exceptions:
                best = max(best, len(parts) - i - 1)
                break
            if cand in self.rules:
                best = max(best, len(parts) - i)
            if i + 1 < len(parts) and ".".join(parts[i + 1:]) in self.wild:
                best = max(best, len(parts) - i)
        n = min(len(parts), best + 1)
        out = ".".join(parts[-n:]) if re.search(r"[a-z]", host) else host
        self.cache[host] = out
        return out


class DomainList:
    def __init__(self, name, block, allow=()):
        self.name, self.block, self.allow = name, set(block), set(allow)

    def hit(self, host):
        parts = host.split(".")
        found = None
        for i in range(len(parts) - 1):
            d = ".".join(parts[i:])
            if d in self.allow:
                return None
            if found is None and d in self.block:
                found = d
        return found


def load_refs(ref):
    ref = Path(ref)
    lists = []
    p = ref / "adguard-dns.txt"
    if p.exists():
        block, allow = set(), set()
        for line in p.read_text(encoding="utf-8", errors="replace").splitlines():
            m = re.match(r"^(@@)?\|\|([a-z0-9._-]+)\^(\$.*)?\|?$", line.strip())
            if not m:
                continue
            opts = m.group(3) or ""
            if "badfilter" in opts or "client=" in opts or "ctag=" in opts:
                continue
            (allow if m.group(1) else block).add(m.group(2))
        lists.append(DomainList("adguard", block, allow))
    p = ref / "hagezi-pro.txt"
    if p.exists():
        lists.append(DomainList("hagezi", {l.strip() for l in p.read_text().splitlines() if l.strip() and not l.startswith("#")}))
    lists.append(DomainList("major", MAJOR))
    # Ad-recovery services put ads back through their own domains once an ad blocker is detected.
    # DNS lists leave them out (blocking the loader can break the site), so they are counted apart.
    lists.append(DomainList("recovery", AD_RECOVERY))
    # the headline: AdGuard's ad and tracker domains plus the ad-recovery domains
    adguard = next((l for l in lists if l.name == "adguard"), None)
    if adguard:
        lists.insert(0, DomainList("all", adguard.block | set(AD_RECOVERY), adguard.allow))
    return lists


def load_run(path):
    pages = {}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if line.strip():
            rec = json.loads(line)
            pages[rec["url"]] = rec
    return pages


def page_ok(rec):
    return "error" not in rec and rec.get("textLength", 0) >= 200 and rec.get("href", "").startswith("http")


def analyse(rec, lists, psl):
    page_host = (urlsplit(rec.get("href") or rec["url"]).hostname or "").lower()
    page_site = psl.site(page_host)
    out = {"requests": 0, "third": 0, "hosts": {l.name: Counter() for l in lists}, "count": Counter()}
    for item in rec.get("res", []):
        url = item[0]
        if not url.startswith("http"):
            continue
        host = (urlsplit(url).hostname or "").lower()
        if not host:
            continue
        out["requests"] += 1
        if psl.site(host) == page_site:
            continue
        out["third"] += 1
        for l in lists:
            if l.hit(host):
                out["count"][l.name] += 1
                out["hosts"][l.name][host] += 1
    visible = rec.get("adVisible", []) or []
    ad_frames = []
    for src in rec.get("iframes", []) or []:
        h = (urlsplit(src).hostname or "").lower()
        if h and psl.site(h) != page_site and any(l.hit(h) for l in lists):
            ad_frames.append(h)
    out["adVisible"] = len(visible) + len(ad_frames)
    out["wall"] = bool(rec.get("adblockWall") or rec.get("adblockText"))
    return out


def pct(part, whole):
    return f"{100.0 * part / whole:.1f}%" if whole else "n/a"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("runs", nargs="+")
    ap.add_argument("--ref", default=str(HERE / "ref"))
    ap.add_argument("--base", default=None, help="label of the run the others are compared with (default: the first non-'none' run)")
    ap.add_argument("--md", default=None)
    ap.add_argument("--top", type=int, default=25)
    args = ap.parse_args()

    psl = Suffixes(Path(args.ref) / "public_suffix_list.dat")
    lists = load_refs(args.ref)
    runs = [(Path(p).stem, load_run(p)) for p in args.runs]
    common = set.intersection(*[{u for u, r in pages.items() if page_ok(r)} for _, pages in runs])
    labels = [l for l, _ in runs]
    none_label = next((l for l in labels if l.startswith("none")), None)
    base = args.base or next((l for l in labels if not l.startswith("none")), labels[0])

    stats, hosts = {}, {}
    for label, pages in runs:
        tot = Counter()
        hc = {l.name: Counter() for l in lists}
        per_page = {}
        for url in common:
            a = analyse(pages[url], lists, psl)
            per_page[url] = a
            tot["requests"] += a["requests"]
            tot["third"] += a["third"]
            tot["adVisible"] += a["adVisible"]
            tot["pagesAdVisible"] += 1 if a["adVisible"] else 0
            tot["walls"] += 1 if a["wall"] else 0
            for l in lists:
                tot[l.name] += a["count"][l.name]
                tot["pages_" + l.name] += 1 if a["count"][l.name] else 0
                hc[l.name].update(a["hosts"][l.name])
        stats[label] = (tot, per_page)
        hosts[label] = hc

    all_pages = {l: sum(1 for r in pages.values() if page_ok(r)) for l, pages in runs}
    lines = [f"# Ad-block benchmark: {', '.join(labels)}", "",
             f"Pages compared: **{len(common)}** (answered in every run; loaded per run: "
             + ", ".join(f"{l} {all_pages[l]}/{len(p)}" for l, p in runs) + ")", ""]
    head = "| Measure | " + " | ".join(labels) + " |"
    lines += [head, "|---|" + "---|" * len(labels)]

    def row(name, key, fmt=lambda v, l: str(v)):
        lines.append(f"| {name} | " + " | ".join(fmt(stats[l][0][key], l) for l in labels) + " |")

    row("Requests (all)", "requests")
    row("Third-party requests", "third")
    for l in lists:
        def f(v, lab, name=l.name):
            s = f"{v}"
            extra = []
            if none_label and lab != none_label:
                extra.append(f"{pct(stats[none_label][0][name] - v, stats[none_label][0][name])} blocked")
            if lab != base and not lab.startswith("none") and base in stats:
                b = stats[base][0][name]
                extra.append(f"{pct(b - v, b)} fewer than {base}")
            return s + (f" ({'; '.join(extra)})" if extra else "")
        row(f"Ad/tracker requests that got through — {l.name}", l.name, f)
        row(f"Pages with {l.name} ad/tracker requests", "pages_" + l.name)
    row("Visible ad spaces (slots + ad iframes)", "adVisible")
    row("Pages with a visible ad space", "pagesAdVisible")
    row("Pages showing an anti-adblock message", "walls")
    lines.append("")

    for label in labels:
        if label.startswith("none"):
            continue
        lines += [f"## What still got through with {label} (by host, adguard list)", ""]
        for h, n in hosts[label]["adguard"].most_common(args.top):
            lines.append(f"- {h}: {n}")
        lines.append("")
        per = stats[label][1]
        worst = sorted(per.items(), key=lambda kv: -kv[1]["count"]["adguard"])[:15]
        lines += [f"### Pages with the most leaks ({label})", ""]
        for url, a in worst:
            if a["count"]["adguard"]:
                lines.append(f"- {url}: {a['count']['adguard']} ({', '.join(h for h, _ in a['hosts']['adguard'].most_common(5))})")
        lines.append("")

    text = "\n".join(lines) + "\n"
    if args.md:
        Path(args.md).write_text(text)
    print(text)


if __name__ == "__main__":
    main()

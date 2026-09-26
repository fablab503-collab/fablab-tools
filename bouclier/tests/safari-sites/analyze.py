#!/usr/bin/env python3
"""Summarise a run of run.sh: which pages loaded, which ad and tracker requests still got through,
which ad spaces stayed visible. Writes results/<run>.md next to the .jsonl.

usage: analyze.py [results/<run>.jsonl]   (default: the newest run)

"Got through" is judged two ways:
  * rules:   the request matches one of Bouclier's own enabled block rules (and no allow rule), so
             Safari should have blocked it. These point at a rule Safari could not apply.
  * network: the request went to a well-known ad or tracking network. These point at gaps in the
             filter lists (or requests the lists allow on purpose, e.g. for a site to work).
Blocked requests never start, so they are not in the page's list at all.
"""
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path
from urllib.parse import urlsplit

HERE = Path(__file__).resolve().parent
RULES = HERE.parent.parent / "extension" / "rules"
ENABLED = ["ads", "privacy", "french", "safety", "antiadblock"]  # the rulesets on by default

AD_NETWORKS = """doubleclick.net googlesyndication.com googleadservices.com google-analytics.com googletagservices.com
adservice.google.com amazon-adsystem.com adnxs.com criteo.com criteo.net taboola.com outbrain.com rubiconproject.com
pubmatic.com openx.net casalemedia.com smartadserver.com teads.tv adsrvr.org scorecardresearch.com quantserve.com
moatads.com hotjar.com 3lift.com adform.net bidswitch.net mathtag.com yieldmo.com sharethrough.com seedtag.com
indexww.com lijit.com sonobi.com adsafeprotected.com doubleverify.com krxd.net demdex.net everesttech.net
omtrdc.net 2mdn.net adsymptotic.com media.net contextweb.com 33across.com gumgum.com rlcdn.com bluekai.com
chartbeat.com chartbeat.net xiti.com ati-host.net quantcount.com permutive.com permutive.app sddan.com
""".split()

SECOND_LEVEL = {"co.uk", "org.uk", "ac.uk", "com.au", "net.au", "co.jp", "com.br", "co.nz", "com.mx", "co.in", "gouv.fr"}


def site_of(host):
    parts = host.split(".")
    if len(parts) >= 3 and ".".join(parts[-2:]) in SECOND_LEVEL:
        return ".".join(parts[-3:])
    return ".".join(parts[-2:])


def under(host, domains):
    return any(host == d or host.endswith("." + d) for d in domains)


# ------------------------------------------------------------------ Bouclier's rules, as regexes

def filter_regex(f):
    rx, i = "", 0
    if f.startswith("||"):
        rx, i = r"^[a-z][a-z0-9+.-]*://(?:[^/?#]*\.)?", 2
    elif f.startswith("|"):
        rx, i = "^", 1
    end = f.endswith("|") and len(f) > i
    body = f[i:len(f) - 1] if end else f[i:]
    for ch in body:
        rx += {"*": ".*", "^": r"(?:[^a-zA-Z0-9_.%-]|$)"}.get(ch, re.escape(ch))
    return re.compile(rx + ("$" if end else ""), re.I)


def domain_key(f):
    m = re.match(r"\|\|([a-z0-9.-]+)", f or "", re.I)
    return m.group(1).lower() if m else None


TYPES = {"script": {"script"}, "img": {"image"}, "image": {"image"}, "iframe": {"sub_frame"}, "frame": {"sub_frame"},
         "xmlhttprequest": {"xmlhttprequest"}, "fetch": {"xmlhttprequest"}, "beacon": {"ping", "xmlhttprequest"},
         "video": {"media"}, "audio": {"media"}, "track": {"media"},
         "css": {"image", "font", "stylesheet"}, "link": {"stylesheet", "font", "script", "image", "other"}}
ALL = {"script", "image", "sub_frame", "xmlhttprequest", "ping", "media", "font", "stylesheet", "other", "websocket", "object"}


class Rules:
    def __init__(self):
        self.by_domain, self.generic = defaultdict(list), []
        for name in ENABLED:
            for r in json.loads((RULES / f"{name}.json").read_text()):
                kind = r["action"]["type"]
                if kind not in ("block", "allow", "allowAllRequests"):
                    continue
                c = r["condition"]
                rule = (kind, r.get("priority", 1), c, filter_regex(c["urlFilter"]) if "urlFilter" in c else None, name)
                keys = c.get("requestDomains") or ([domain_key(c.get("urlFilter"))] if domain_key(c.get("urlFilter")) else [])
                if keys:
                    for k in keys:
                        self.by_domain[k.lower()].append(rule)
                else:
                    self.generic.append(rule)

    def candidates(self, host):
        parts = host.split(".")
        for i in range(len(parts) - 1):
            yield from self.by_domain.get(".".join(parts[i:]), ())
        yield from self.generic

    def verdict(self, url, types, page_host):
        host = (urlsplit(url).hostname or "").lower()
        third = site_of(host) != site_of(page_host)
        best = {"block": 0, "allow": 0}
        hit = None
        for kind, prio, c, rx, name in self.candidates(host):
            if kind == "allowAllRequests":
                continue
            if "requestDomains" in c and not under(host, c["requestDomains"]):
                continue
            if under(host, c.get("excludedRequestDomains", ())):
                continue
            if "initiatorDomains" in c and not under(page_host, c["initiatorDomains"]):
                continue
            if under(page_host, c.get("excludedInitiatorDomains", ())):
                continue
            dt = c.get("domainType")
            if (dt == "thirdParty" and not third) or (dt == "firstParty" and third):
                continue
            allowed_types = set(c.get("resourceTypes") or (ALL - {"main_frame"})) - set(c.get("excludedResourceTypes", ()))
            if kind == "block":  # a leak only if the request surely is one of the rule's types
                if not types <= allowed_types:
                    continue
            elif not types & allowed_types:  # an allow rule counts if it may apply
                continue
            if rx and not rx.search(url):
                continue
            if prio > best[kind]:
                best[kind] = prio
                if kind == "block":
                    hit = f"{name}: {c.get('urlFilter') or next((d for d in c.get('requestDomains', []) if under(host, [d])), json.dumps(c)[:80])}"
        return hit if best["block"] and best["block"] > best["allow"] else None

    def page_allowed(self, url):
        host = (urlsplit(url).hostname or "").lower()
        for kind, prio, c, rx, name in self.candidates(host):
            if kind == "allowAllRequests" and (not rx or rx.search(url)) and \
               ("requestDomains" not in c or under(host, c["requestDomains"])):
                return True
        return False


# ------------------------------------------------------------------ report

def main():
    runs = sorted((HERE / "results").glob("*.jsonl"))
    path = Path(sys.argv[1]) if len(sys.argv) > 1 else runs[-1]
    pages = [json.loads(l) for l in path.read_text().splitlines() if l.strip()]
    rules = Rules()
    rows, leak_hosts, net_hosts, problems = [], Counter(), Counter(), []
    totals = Counter()
    for p in pages:
        totals["pages"] += 1
        if "error" in p:
            totals["error"] += 1
            problems.append(f"| {p['n']} | {p['url']} | did not answer: {p['error'][:90]} |")
            rows.append(p | {"verdict": "no answer"})
            continue
        page_host = (urlsplit(p["href"]).hostname or "").lower()
        leaks, nets = [], []
        if not rules.page_allowed(p["href"]):
            for url, initiator in p.get("res", []):
                if not url.startswith("http"):
                    continue
                host = (urlsplit(url).hostname or "").lower()
                hit = rules.verdict(url, TYPES.get(initiator, {"other"} if initiator == "other" else ALL), page_host)
                if hit:
                    leaks.append((url, hit))
                    leak_hosts[host] += 1
                if under(host, AD_NETWORKS) and site_of(host) != site_of(page_host):
                    nets.append(url)
                    net_hosts[site_of(host)] += 1
        p["leaks"], p["nets"] = leaks, nets
        totals["requests"] += p.get("requests", 0)
        totals["leaks"] += len(leaks)
        totals["nets"] += len(nets)
        totals["adVisible"] += len(p.get("adVisible", []))
        thin = p.get("textLength", 0) < 200
        if thin:
            problems.append(f"| {p['n']} | {p['url']} | almost no text on the page ({p.get('textLength', 0)} characters): blank, blocked by the site, or a wall |")
        if p.get("adblockWall") or p.get("adblockText"):
            problems.append(f"| {p['n']} | {p['url']} | mentions ad blockers: “{(p.get('adblockText') or 'Funding Choices wall')[:100]}” |")
        if p.get("adVisible"):
            problems.append(f"| {p['n']} | {p['url']} | {len(p['adVisible'])} ad space(s) still visible: {'; '.join(p['adVisible'][:3])} |")
        rows.append(p)

    out = [f"# Bouclier in Safari: {len(pages)} sites ({path.stem})", ""]
    ok = [p for p in rows if "error" not in p]
    out += [f"- Pages that loaded and answered: **{len(ok)} / {len(pages)}**",
            f"- Requests the pages made after blocking: {totals['requests']}",
            f"- Requests Bouclier's rules say to block but Safari let through: **{totals['leaks']}** on {sum(1 for p in ok if p['leaks'])} pages",
            f"- Requests to well-known ad or tracking networks that got through: **{totals['nets']}** on {sum(1 for p in ok if p['nets'])} pages",
            f"- Ad spaces still visible: **{totals['adVisible']}** on {sum(1 for p in ok if p.get('adVisible'))} pages",
            f"- Pages with a consent pop-up open (ads usually wait for consent): {sum(1 for p in ok if p.get('cmp'))}", ""]
    out += ["## Things to look at", "", "| # | Site | What |", "|---|---|---|"] + (problems or ["| | | nothing |"]) + [""]
    out += ["## Ad and tracking networks that got through (by network)", ""] + \
           [f"- {h}: {n}" for h, n in net_hosts.most_common(25)] + [""]
    out += ["## Requests that match a block rule but got through (by host)", ""] + \
           [f"- {h}: {n}" for h, n in leak_hosts.most_common(25)] + [""]
    out += ["## Every site", "", "| # | Site | Load s | Requests | Rule leaks | Ad-network requests | Visible ads | Consent pop-up |",
            "|---|---|---|---|---|---|---|---|"]
    for p in rows:
        if "error" in p:
            out.append(f"| {p['n']} | {p['url']} | {p.get('seconds', '')} | no answer | | | | |")
        else:
            out.append(f"| {p['n']} | {p['url']} | {p['seconds']} | {p.get('requests', 0)} | {len(p['leaks'])} | {len(p['nets'])} | "
                       f"{len(p.get('adVisible', []))} | {', '.join(p.get('cmp', [])) or ''} |")
    out += ["", "## Details of what got through", ""]
    for p in rows:
        if p.get("leaks") or p.get("nets"):
            out.append(f"### {p['n']}. {p['url']}")
            for url, hit in p["leaks"][:8]:
                out.append(f"- rule leak: `{url[:140]}` ({hit[:80]})")
            for url in p["nets"][:8]:
                out.append(f"- ad network: `{url[:140]}`")
            out.append("")
    md = path.with_suffix(".md")
    md.write_text("\n".join(out) + "\n")
    print("\n".join(out[:9]))
    print(f"\nReport: {md}")


if __name__ == "__main__":
    main()

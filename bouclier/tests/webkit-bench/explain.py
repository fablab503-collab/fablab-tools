#!/usr/bin/env python3
"""Why did an ad or tracker request get through? For one run of BouclierBench, sorts every
third-party request the reference list calls an ad/tracker into:
  * allowed:  a Bouclier block rule matches, but an exception (allow rule) of the lists wins;
  * missed:   a block rule matches and nothing allows it: the engine did not apply it (bug?);
  * unlisted: no Bouclier rule matches at all.

usage: explain.py results/v120-train.jsonl [--ext ../../extension] [--lists ads,privacy,...] [--ref ref]
"""
import argparse
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path
from urllib.parse import urlsplit

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.append(str(HERE.parent / "safari-sites"))  # after ours: safari-sites has its own compare.py
import compare  # noqa: E402
from analyze import filter_regex, domain_key, under, TYPES, ALL  # noqa: E402


class Rules:
    def __init__(self, ext, names):
        self.by_domain, self.generic = defaultdict(list), []
        for name in names:
            path = Path(ext) / "rules" / f"{name}.json"
            if not path.exists():
                continue
            for r in json.loads(path.read_text()):
                kind = r["action"]["type"]
                if kind not in ("block", "allow", "allowAllRequests", "redirect"):
                    continue
                c = r["condition"]
                if "regexFilter" in c:
                    continue
                rule = (kind, r.get("priority", 1), c, filter_regex(c["urlFilter"]) if "urlFilter" in c else None, name, r["id"])
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

    def verdict(self, url, types, page_host, third):
        host = (urlsplit(url).hostname or "").lower()
        best = {"block": (0, None), "allow": (0, None)}
        for kind, prio, c, rx, name, rid in self.candidates(host):
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
            if not types & allowed_types:
                continue
            if rx and not rx.search(url):
                continue
            k = "block" if kind in ("block", "redirect") else "allow"
            if prio > best[k][0]:
                desc = c.get("urlFilter") or next((d for d in c.get("requestDomains", []) if under(host, [d])), "?")
                extra = ""
                if c.get("initiatorDomains"):
                    extra = " on " + ",".join(c["initiatorDomains"][:3])
                best[k] = (prio, f"{name}#{rid}: {desc}{extra}")
        if best["block"][0] and best["block"][0] > best["allow"][0]:
            return "missed", best["block"][1]
        if best["block"][0]:
            return "allowed", best["allow"][1]
        return "unlisted", None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--ext", default=str(HERE.parent.parent / "extension"))
    ap.add_argument("--lists", default="ads,privacy,french,safety,antiadblock,extra")
    ap.add_argument("--ref", default=str(HERE / "ref"))
    ap.add_argument("--judge", default="adguard")
    ap.add_argument("--top", type=int, default=40)
    args = ap.parse_args()
    psl = compare.Suffixes(Path(args.ref) / "public_suffix_list.dat")
    judge = next(l for l in compare.load_refs(args.ref) if l.name == args.judge)
    rules = Rules(args.ext, args.lists.split(","))
    reasons = Counter()
    by = {k: Counter() for k in ("allowed", "missed", "unlisted")}
    why = {k: Counter() for k in ("allowed", "missed")}
    sites_unlisted = Counter()
    examples = {}
    for rec in compare.load_run(args.run).values():
        if not compare.page_ok(rec):
            continue
        page_host = (urlsplit(rec.get("href") or rec["url"]).hostname or "").lower()
        page_site = psl.site(page_host)
        for url, initiator, frame in rec.get("res", []):
            host = (urlsplit(url).hostname or "").lower()
            if not host or psl.site(host) == page_site or not judge.hit(host):
                continue
            types = TYPES.get(initiator, {"other"} if initiator == "other" else ALL)
            reason, rule = rules.verdict(url, types, page_host, True)
            reasons[reason] += 1
            by[reason][psl.site(host)] += 1
            if rule:
                why[reason][rule] += 1
            if reason == "unlisted":
                sites_unlisted[host] += 1
            examples.setdefault((reason, psl.site(host)), url[:160])
    total = sum(reasons.values())
    print(f"# Why ad/tracker requests got through ({Path(args.run).stem}, judged by {args.judge})\n")
    for k in ("allowed", "missed", "unlisted"):
        print(f"- {k}: {reasons[k]} ({100.0 * reasons[k] / total:.1f}%)" if total else f"- {k}: 0")
    for k in ("allowed", "missed", "unlisted"):
        print(f"\n## {k}: by registrable domain\n")
        for d, n in by[k].most_common(args.top):
            print(f"- {d}: {n}   e.g. {examples.get((k, d), '')}")
        if k in why:
            print(f"\n### {k}: rules involved\n")
            for r, n in why[k].most_common(args.top):
                print(f"- {n} × {r}")
    print("\n## unlisted: by host\n")
    for h, n in sites_unlisted.most_common(args.top * 2):
        print(f"- {h}: {n}")


if __name__ == "__main__":
    main()

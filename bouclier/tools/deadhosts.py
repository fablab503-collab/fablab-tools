#!/usr/bin/env python3
"""Finds blocked domains that no longer exist, so the Safari rule budget is not spent on them.

Safari compiles every enabled rule into one content blocker capped at 150,000 rules, and one
blocked domain costs one rule. A good part of the domains in the big lists has been dead for
years (the name does not exist any more: NXDOMAIN). A page cannot load anything from such a name,
so a rule for it blocks nothing. convert.py leaves out the domains listed in dead-hosts.json.

A domain only counts as dead when two independent resolvers both answer NXDOMAIN (the name and,
by the DNS rules, everything under it does not exist). Time-outs, server failures and names that
exist without an address are kept. Names from the list are re-checked on every run, so a domain
that comes back is blocked again at the next build.

usage: python3 tools/deadhosts.py --cache lists-cache [--out tools/dead-hosts.json] [--workers 200]
"""
import argparse
import asyncio
import json
import os
import re
import socket
import sys
import time
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import convert  # noqa: E402

RESOLVERS = ["8.8.8.8", "1.1.1.1", "9.9.9.9"]


def blocked_hosts(lists_json, cache):
    """Domains of the pure 'block this whole domain' rules of every downloadable list."""
    hosts = set()
    for cfg in json.load(open(lists_json, encoding="utf-8"))["lists"]:
        path = os.path.join(cache, cfg["id"] + ".txt")
        if cfg.get("format") == "builtin-urlclean" or not os.path.exists(path):
            continue  # Bouclier's own additions (tools/extra-*.txt) are checked by hand
        text = open(path, encoding="utf-8", errors="replace").read()
        rules, *_ = convert.compile_list(cfg, text, convert.Stats())
        for r in rules:
            c = r["condition"]
            if r["action"]["type"] == "block" and "requestDomains" in c and "urlFilter" not in c:
                hosts.update(h for h in c["requestDomains"] if not re.match(r"^\d+(\.\d+){3}$", h))
    return hosts


async def check_async(hosts, workers):
    """Asks three public resolvers in turn (spreading the load so none of them throttles us); a name
    only counts as dead when a second, different resolver also answers NXDOMAIN. Names that time out
    are asked again, more slowly, at the end; if they still do not answer they are kept (alive)."""
    import dns.asyncresolver
    import dns.exception
    import dns.resolver

    def resolver(ns):
        r = dns.asyncresolver.Resolver(configure=False)
        r.nameservers = [ns]
        r.timeout = 4
        r.lifetime = 8
        return r

    pool = [resolver(ns) for ns in RESOLVERS]

    async def verdict(r, host):
        try:
            await r.resolve(host, "A")
            return "ok"
        except dns.resolver.NXDOMAIN:
            return "nx"
        except dns.resolver.NoAnswer:
            return "nodata"
        except (dns.exception.Timeout, dns.resolver.NoNameservers, dns.resolver.LifetimeTimeout):
            return "error"
        except Exception:
            return "error"

    async def one(host, sem, i):
        async with sem:
            first = pool[i % len(pool)]
            v = await verdict(first, host)
            if v == "nx":
                second = pool[(i + 1) % len(pool)]
                v2 = await verdict(second, host)
                return host, "nx" if v2 == "nx" else v2
            return host, v

    out = {}
    todo = sorted(hosts)
    for round_no, width in enumerate((workers, max(20, workers // 5)), 1):
        sem = asyncio.Semaphore(width)
        tasks = [one(h, sem, i + round_no) for i, h in enumerate(todo)]
        done = 0
        for fut in asyncio.as_completed(tasks):
            h, v = await fut
            out[h] = v
            done += 1
            if done % 10000 == 0:
                print(f"  round {round_no}: {done}/{len(tasks)}", file=sys.stderr)
        todo = [h for h in todo if out.get(h) == "error"]
        print(f"  round {round_no} done, {len(todo)} names to ask again", file=sys.stderr)
        if not todo:
            break
    return out


def check_threads(hosts, workers):
    def one(host):
        for attempt in range(2):
            try:
                socket.getaddrinfo(host, None)
                return host, "ok"
            except socket.gaierror as e:
                if e.errno == socket.EAI_NONAME:
                    return host, "nx"
                if e.errno == getattr(socket, "EAI_NODATA", -5):
                    return host, "nodata"
        return host, "error"
    with ThreadPoolExecutor(workers) as ex:
        return dict(ex.map(one, sorted(hosts)))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--lists", default=os.path.join(os.path.dirname(__file__), "lists.json"))
    ap.add_argument("--cache", required=True)
    ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "dead-hosts.json"))
    ap.add_argument("--workers", type=int, default=240)
    args = ap.parse_args()

    hosts = blocked_hosts(args.lists, args.cache)
    print(f"checking {len(hosts):,} blocked domains", file=sys.stderr)
    t = time.time()
    try:
        import dns.asyncresolver  # noqa: F401
        results = asyncio.run(check_async(hosts, args.workers))
        how = "dnspython, NXDOMAIN from " + " and ".join(RESOLVERS[:2])
    except ImportError:
        results = check_threads(hosts, min(args.workers, 64))
        how = "system resolver (install dnspython for a second, independent check)"
    dead = sorted(h for h, v in results.items() if v == "nx")
    counts = {k: sum(1 for v in results.values() if v == k) for k in ("ok", "nx", "nodata", "error")}
    json.dump({
        "_comment": "Blocked domains that do not exist any more (NXDOMAIN). convert.py leaves them out of the Safari rules. "
                    "Regenerate with tools/deadhosts.py before each release.",
        "checked": time.strftime("%Y-%m-%d %H:%M", time.gmtime()) + " UTC",
        "how": how,
        "counts": counts,
        "dead": dead,
    }, open(args.out, "w", encoding="utf-8"), indent=0)
    print(f"{counts} in {time.time() - t:.0f}s -> {len(dead):,} dead domains in {args.out}", file=sys.stderr)


if __name__ == "__main__":
    main()

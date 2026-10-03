#!/bin/bash
# Bouclier benchmark in the real Safari: the two public ad-block test pages
# (adblock.turtlecute.org, same host list as d3ward's test, and adblock-tester.com),
# plus a network check of every test host so dead hosts are not credited to Bouclier.
#
# usage: tests/safari-sites/bench.sh [label]      -> results/bench-<label>.json and .md
# Needs Safari > Settings > Developer > Allow JavaScript from Apple Events.
set -u
cd "$(dirname "$0")"
LABEL="${1:-$(date +%Y%m%d-%H%M)}"
mkdir -p results
OUT="$PWD/results/bench-$LABEL.json"
osascript bench.applescript "$OUT" "$(cat bench-probe-turtle.js)" "$(cat bench-probe-tester.js)" || exit 1
# which test hosts answer at all (any HTTP answer = alive); the test counts dead hosts as blocked
python3 - "$OUT" <<'PY'
import json, re, subprocess, sys, concurrent.futures as cf
out = sys.argv[1]
d = json.load(open(out))
log = d.get("turtle", {}).get("log", "")
rows = re.findall(r"([a-z0-9][a-z0-9.-]+\.[a-z]{2,}) - (blocked|not blocked)", log)
def alive(h):
    r = subprocess.run(["curl", "-s", "-o", "/dev/null", "-I", "--max-time", "8", "-w", "%{http_code}",
                        f"https://{h}/fakepage.html"], capture_output=True, text=True)
    return h, r.stdout.strip() not in ("", "000")
with cf.ThreadPoolExecutor(16) as ex:
    live = dict(ex.map(alive, sorted({h for h, _ in rows})))
d["turtle"]["hosts"] = [{"host": h, "verdict": v, "alive": live.get(h)} for h, v in rows]
json.dump(d, open(out, "w"), indent=1)
hosts = d["turtle"]["hosts"]
blocked = [x for x in hosts if x["verdict"] == "blocked"]
live_hosts = [x for x in hosts if x["alive"]]
live_blocked = [x for x in live_hosts if x["verdict"] == "blocked"]
m = re.search(r"(\d+)\s*points?\s*out of\s*(\d+)", d.get("tester", {}).get("text", ""))
lines = [f"# Bouclier benchmark {d['label']}", "",
         f"- adblock.turtlecute.org: **{d['turtle'].get('score', '?').strip()}** "
         f"({len(blocked)} of {len(hosts)} hosts blocked; scripts: {d['turtle'].get('summary', '').strip()[:80]})",
         f"- Live test hosts blocked by Bouclier: **{len(live_blocked)} / {len(live_hosts)}**",
         f"- adblock-tester.com: **{m.group(1) + ' / ' + m.group(2) if m else '?'}**", "",
         "## Test hosts that got through", ""] + \
        [f"- {x['host']}" for x in hosts if x["verdict"] != "blocked"] + ["", "## adblock-tester.com failures", ""] + \
        [f"- {l.strip()}" for l in d.get("tester", {}).get("text", "").splitlines() if "fail" in l.lower()][:40]
open(out[:-5] + ".md", "w").write("\n".join(lines) + "\n")
print("\n".join(lines[:6]))
PY

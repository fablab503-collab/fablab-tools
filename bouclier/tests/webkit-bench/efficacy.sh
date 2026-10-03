#!/bin/bash
# Bouclier's weekly efficacy check (Daniel, 30 Sept 2026): the independent ad-block test
# adblock.turtlecute.org (source: github.com/Turtlecute33/adblocktest, CC BY-NC-SA 4.0: only visited,
# never copied into Bouclier) loaded in WebKit with Bouclier, and its score. Below the alert line
# (93 % by default), or when the last 100-site scan is more than 4 weeks old, the 100 sites of
# ../safari-sites/sites.tsv are scanned again, without and with Bouclier, and compared with the last
# scan: what got through, on which pages, what changed.
#
# usage: tests/webkit-bench/efficacy.sh [--scan] [--alert 93] [extension folder]
#   default extension: the installed app's (/Applications/Bouclier.app)
#   results/efficacy-<date>.md (the report), results/efficacy-log.tsv (one line per run)
# Prints the report; exit 0 = at or above the alert line, 2 = below it, 1 = the test could not run.
set -uo pipefail
cd "$(dirname "$0")"
SCAN=0 ALERT=93 EXT="/Applications/Bouclier.app/Contents/PlugIns/Bouclier Extension.appex/Contents/Resources"
while [ $# -gt 0 ]; do
  case "$1" in
    --scan) SCAN=1 ;;
    --alert) ALERT="$2"; shift ;;
    *) EXT="$1" ;;
  esac
  shift
done
DAY=$(date +%Y-%m-%d)
STAMP=$(date +%Y%m%d-%H%M)
mkdir -p results build
X=build/BouclierBench.app/Contents/MacOS/BouclierBench
[ -x "$X" ] || ./build.sh > results/efficacy-build.log 2>&1 || { echo "BouclierBench does not build: results/efficacy-build.log"; exit 1; }
[ -d "$EXT" ] || { echo "No extension at $EXT"; exit 1; }
APP_PLIST="$(dirname "$(dirname "$(dirname "$(dirname "$EXT")")")")/Info.plist"
if defaults read "$APP_PLIST" CFBundleVersion > /dev/null 2>&1; then
  VERSION="$(defaults read "$APP_PLIST" CFBundleShortVersionString) ($(defaults read "$APP_PLIST" CFBundleVersion))"
else   # an extension folder, not an installed app: its manifest's version
  VERSION="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' "$EXT/manifest.json" 2>/dev/null || echo '?') (folder)"
fi
SAFARI_VERSION="$(defaults read /Applications/Safari.app/Contents/Info.plist CFBundleShortVersionString 2>/dev/null || echo 26.0)"

# 1. the test page, as a visitor sees it (it waits for every address before giving its score)
printf 'test\thttps://adblock.turtlecute.org/\n' > build/turtle.tsv
"$X" --sites build/turtle.tsv --out "results/turtle-$STAMP.jsonl" --probe ../safari-sites/bench-probe-turtle.js \
  --parallel 1 --settle 30 --label "turtle-$STAMP" --safari-version "$SAFARI_VERSION" --extension "$EXT" \
  2> "results/turtle-$STAMP.log"

# 2. its score, and which of its addresses still answer at all (it counts a dead address as blocked)
SCORE=$(python3 - "results/turtle-$STAMP.jsonl" "results/turtle-$STAMP.json" <<'PY'
import concurrent.futures as cf, json, re, subprocess, sys
src, out = sys.argv[1], sys.argv[2]
try:
    d = json.loads(open(src).readline())
except Exception:
    print("ERROR the test page did not load"); sys.exit(0)
log = d.get("log") or ""
rows = re.findall(r"([a-z0-9][a-z0-9.-]+\.[a-z]{2,}) - (blocked|not blocked)", log)
m = re.search(r"Total\s*:\s*(\d+)\s*Blocked\s*:\s*(\d+)", log.replace("\n", " "))
if not rows or not m:
    print("ERROR the test page gave no score"); sys.exit(0)
total, blocked = int(m.group(1)), int(m.group(2))
def alive(h):
    r = subprocess.run(["curl", "-s", "-o", "/dev/null", "-I", "--max-time", "8", "-w", "%{http_code}", f"https://{h}/"],
                       capture_output=True, text=True)
    return h, r.stdout.strip() not in ("", "000")
with cf.ThreadPoolExecutor(16) as ex:
    live = dict(ex.map(alive, sorted({h for h, _ in rows})))
hosts = [{"host": h, "verdict": v, "alive": live.get(h)} for h, v in rows]
json.dump({"total": total, "blocked": blocked, "hosts": hosts, "summary": d.get("summary")}, open(out, "w"), indent=1)
lh = [x for x in hosts if x["alive"]]
lb = [x for x in lh if x["verdict"] == "blocked"]
through = [x["host"] for x in hosts if x["verdict"] != "blocked"]
print(f"{100 * blocked / total:.1f} {blocked} {total} {len(lb)} {len(lh)} {','.join(through) or '-'}")
PY
)
if [ "${SCORE%% *}" = "ERROR" ]; then
  echo "Efficacy check $DAY: the test could not run (${SCORE#ERROR }). See results/turtle-$STAMP.log"
  printf '%s\t%s\t%s\t\t\t\t\t%s\n' "$DAY" "$VERSION" "error" "${SCORE#ERROR }" >> results/efficacy-log.tsv
  exit 1
fi
read -r PCT BLOCKED TOTAL LIVE_BLOCKED LIVE THROUGH <<< "$SCORE"
BELOW=$(python3 -c "print(1 if float('$PCT') < float('$ALERT') else 0)")

# 3. the 100-site scan: asked for, below the line, or the last one is more than 4 weeks old
LAST_SCAN=$(ls -t results/scan-*-with.jsonl 2>/dev/null | head -1)
OLD=1
[ -n "$LAST_SCAN" ] && [ -n "$(find "$LAST_SCAN" -mtime -28 2>/dev/null)" ] && OLD=0
SCAN_MD=""
if [ "$SCAN" = 1 ] || [ "$BELOW" = 1 ] || [ "$OLD" = 1 ]; then
  ./run.sh "scan-$STAMP-none" none ../safari-sites/sites.tsv 3
  ./run.sh "scan-$STAMP-with" "$EXT" ../safari-sites/sites.tsv 3
  [ -s ref/adguard-dns.txt ] && [ -n "$(find ref/adguard-dns.txt -mtime -28)" ] || ./fetch_refs.sh > results/efficacy-refs.log 2>&1
  ARGS=("results/scan-$STAMP-none.jsonl")
  [ -n "$LAST_SCAN" ] && ARGS+=("$LAST_SCAN")
  ARGS+=("results/scan-$STAMP-with.jsonl")
  python3 compare.py "${ARGS[@]}" --md "results/scan-$STAMP.md" > /dev/null 2>&1
  SCAN_MD="results/scan-$STAMP.md"
fi

# 4. the report and the log line
REPORT="results/efficacy-$DAY.md"
{
  echo "# Bouclier efficacy check, $DAY"
  echo
  echo "- Bouclier $VERSION, WebKit (Safari $SAFARI_VERSION engine), extension: \`$EXT\`"
  echo "- adblock.turtlecute.org: **$PCT %** ($BLOCKED of $TOTAL blocked); addresses that still answer: $LIVE_BLOCKED of $LIVE blocked"
  echo "- Alert line: $ALERT % — $([ "$BELOW" = 1 ] && echo '**below: find why, improve, rescan**' || echo 'above')"
  [ "$THROUGH" != "-" ] && echo "- Got through: ${THROUGH//,/, }"
  if [ -n "$SCAN_MD" ]; then
    echo
    echo "## 100-site scan (without Bouclier, last scan if any, now)"
    echo
    sed -n '3,40p' "$SCAN_MD"
  fi
} > "$REPORT"
[ -s results/efficacy-log.tsv ] || printf 'date\tbouclier\tturtlecute %%\tblocked\ttotal\tlive blocked\tlive\tgot through\tscan\n' > results/efficacy-log.tsv
printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$DAY" "$VERSION" "$PCT" "$BLOCKED" "$TOTAL" "$LIVE_BLOCKED" "$LIVE" "$THROUGH" "${SCAN_MD:-}" >> results/efficacy-log.tsv
cat "$REPORT"
[ "$BELOW" = 1 ] && exit 2
exit 0

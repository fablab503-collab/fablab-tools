#!/bin/bash
# One benchmark run in WebKit: tests/webkit-bench/run.sh <label> <extension folder|none> [sites.tsv] [parallel]
#   results/<label>.jsonl (one line per page) and results/<label>.log
set -uo pipefail
cd "$(dirname "$0")"
LABEL="$1"; EXT="${2:-none}"; SITES="${3:-../safari-sites/sites.tsv}"; PAR="${4:-3}"
mkdir -p results
[ -x build/BouclierBench.app/Contents/MacOS/BouclierBench ] || ./build.sh
SAFARI_VERSION="$(defaults read /Applications/Safari.app/Contents/Info.plist CFBundleShortVersionString 2>/dev/null || echo 26.0)"
ARGS=(--sites "$SITES" --out "results/$LABEL.jsonl" --probe ../safari-sites/probe.js --parallel "$PAR" --label "$LABEL" --safari-version "$SAFARI_VERSION")
[ "$EXT" != none ] && ARGS+=(--extension "$EXT")
build/BouclierBench.app/Contents/MacOS/BouclierBench "${ARGS[@]}" 2> "results/$LABEL.log"
echo "exit $? $(grep -c . "results/$LABEL.jsonl" 2>/dev/null) pages" >> "results/$LABEL.log"

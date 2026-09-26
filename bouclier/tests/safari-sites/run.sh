#!/bin/bash
# Loads every site of sites.tsv in a Safari window of its own, with Bouclier on, and records what
# each page loaded in results/<run>.jsonl (then run analyze.py). Your other Safari windows are
# never read or touched. Needs Safari > Settings > Developer > Allow JavaScript from Apple Events.
#
# usage: tests/safari-sites/run.sh [sites.tsv] [first] [last]      SHOTS=1 also saves screenshots
set -u
cd "$(dirname "$0")"
SITES="${1:-sites.tsv}"; FIRST="${2:-1}"; LAST="${3:-100000}"
RUN="${RUN:-$(date +%Y%m%d-%H%M)}"
OUT="results/$RUN.jsonl"; STATUS="results/$RUN.status"; SHOTDIR="results/$RUN-shots"
mkdir -p results
[ "${SHOTS:-0}" = 1 ] && mkdir -p "$SHOTDIR"
PROBE="$(cat probe.js)"

# a new window of our own: the one whose id was not there before (only window ids are read)
WID="$(osascript -e 'tell application "Safari"' -e 'set oldIds to id of every window' \
  -e 'make new document with properties {URL:"about:blank"}' -e 'delay 1' -e 'set wid to missing value' \
  -e 'set newIds to id of every window' -e 'repeat with x in newIds' -e 'if oldIds does not contain (contents of x) then set wid to (contents of x)' \
  -e 'end repeat' -e 'if wid is missing value then error "no new window"' -e 'return wid' -e 'end tell')" \
  || { echo "Could not open a Safari window" | tee "$STATUS"; exit 1; }
echo "window $WID" > "$STATUS"
# desktop size (1440 x 1000 points), so sites show their desktop layout and their desktop ads
osascript -e "tell application \"Safari\" to set bounds of window id $WID to {40, 40, 1480, 1040}" >/dev/null 2>&1 || true

# The Mac may be on a phone hotspot that drops for a few seconds: wait until the site answers.
wait_for_network() {
  local host tries=0
  host="$(printf '%s' "$1" | sed -E 's#^https?://([^/]+).*#\1#')"
  until curl -s -o /dev/null --max-time 6 -I "https://$host/" || curl -s -o /dev/null --max-time 6 -I https://www.apple.com/; do
    tries=$((tries + 1)); [ "$tries" -ge 12 ] && return 1
    sleep 5
  done
}

i=0
grep -v '^#' "$SITES" | while IFS="$(printf '\t')" read -r category url; do
  i=$((i + 1))
  [ "$i" -lt "$FIRST" ] && continue
  [ "$i" -gt "$LAST" ] && break
  echo "$i $url" > "$STATUS"
  start=$(date +%s)
  for attempt in 1 2; do
  wait_for_network "$url" || echo "no network before $url" >> "results/$RUN.log"
  raw="$(osascript - "$WID" "$url" "$PROBE" 2>&1 <<'AS'
on run argv
  set wid to (item 1 of argv) as integer
  set u to item 2 of argv
  set probe to item 3 of argv
  tell application "Safari"
    set t to current tab of window id wid
    set URL of t to u
    delay 3
    repeat 30 times
      try
        if (do JavaScript "document.readyState" in t) is "complete" then exit repeat
      end try
      delay 1
    end repeat
    delay 4
    try -- scroll down and back so lazy ads get their chance to load
      do JavaScript "window.scrollTo(0, document.documentElement.scrollHeight / 3)" in t
      delay 2
      do JavaScript "window.scrollTo(0, document.documentElement.scrollHeight * 2 / 3)" in t
      delay 2
      do JavaScript "window.scrollTo(0, 0)" in t
      delay 2
    end try
    return do JavaScript probe in t
  end tell
end run
AS
)"
  # retry once when the page did not load (error page, nothing there, or still the previous site)
  case "$raw" in
    *'"href":"safari-resource:'*|*'"textLength":0,'*) sleep 5; continue ;;
  esac
  target="$(printf '%s' "$url" | sed -E 's#^https?://(www\.)?([^/]+).*#\2#')"
  case "$raw" in *"\"href\":\"http"*"$target"*|*"execution error"*) break ;; *) sleep 5; continue ;; esac
  done
  secs=$(( $(date +%s) - start ))
  case "$raw" in *"window id"*"doesn"*|*"Invalid index"*|*"Can’t get window"*|*"Can't get window"*)
    echo "The test window was closed: stopped at $i" | tee "$STATUS"; exit 1 ;;
  esac
  if [ "${SHOTS:-0}" = 1 ]; then
    shot="$SHOTDIR/$(printf %03d "$i").png"
    screencapture -x -o -l "$WID" "$shot" 2>/dev/null && sips -Z 1400 "$shot" >/dev/null 2>&1 || true
  fi
  python3 - "$i" "$category" "$url" "$secs" "$raw" "$attempt" >> "$OUT" <<'PY'
import json, sys
i, category, url, secs, raw = sys.argv[1:6]
try:
    rec = json.loads(raw)
    if not isinstance(rec, dict):
        raise ValueError
except ValueError:
    rec = {"error": raw.strip()[-300:] or "no answer from the page"}
rec.update(n=int(i), category=category, url=url, seconds=int(secs), attempts=int(sys.argv[6]))
print(json.dumps(rec, ensure_ascii=False))
PY
done
grep -q stopped "$STATUS" && exit 1  # the window is gone already
osascript -e "tell application \"Safari\" to close window id $WID" >/dev/null 2>&1 || true
echo "DONE $(grep -c . "$OUT" 2>/dev/null) pages" > "$STATUS"

#!/bin/bash
# Checks that site-limited exceptions stay on their sites in Safari (the bug found 2026-09-23:
# Safari applied "allow gpt.js on these sites" everywhere). Opens a Safari window of its own.
#   example.com:     Google's ad scripts, Tag Manager, Criteo and Taboola must be BLOCKED, jQuery LOADED
#   accuweather.com: gpt.js must LOAD (EasyList allows it there), adsbygoogle must stay BLOCKED
# usage: tests/safari-sites/exceptions.sh   (result in results/exceptions.txt, exit 1 on a failure)
set -u
cd "$(dirname "$0")"; mkdir -p results
check() {  # site, then key=EXPECTED pairs
  local site="$1"; shift
  local got JS
  JS="window.__r = {}; const T = {gpt: 'https://securepubads.g.doubleclick.net/tag/js/gpt.js?t=$RANDOM', adsbygoogle: 'https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js?t=$RANDOM', gtag: 'https://www.googletagmanager.com/gtag/js?id=G-T$RANDOM', criteo: 'https://static.criteo.net/js/ld/publishertag.js?t=$RANDOM', taboola: 'https://cdn.taboola.com/libtrc/test/loader.js?t=$RANDOM', jquery: 'https://code.jquery.com/jquery-3.7.1.min.js?t=$RANDOM'}; for (const [k, u] of Object.entries(T)) { const s = document.createElement('script'); s.src = u; s.onload = () => window.__r[k] = 'LOADED'; s.onerror = () => window.__r[k] = 'BLOCKED'; document.head.appendChild(s); } 'ok'"
  got="$(osascript - "$site" "$JS" <<'AS'
on run argv
  tell application "Safari"
    set oldIds to id of every window
    make new document with properties {URL:(item 1 of argv)}
    delay 1
    set wid to missing value
    set newIds to id of every window
    repeat with x in newIds
      if oldIds does not contain (contents of x) then set wid to (contents of x)
    end repeat
    if wid is missing value then error "could not find the test window"
  end tell
  delay 8
  tell application "Safari" to do JavaScript (item 2 of argv) in current tab of window id wid
  delay 6
  tell application "Safari"
    set r to do JavaScript "JSON.stringify(window.__r)" in current tab of window id wid
    close window id wid
  end tell
  return r
end run
AS
)"
  echo "$site $got"
  local ok=1
  for pair in "$@"; do
    case "$got" in *"\"${pair%%=*}\":\"${pair#*=}\""*) ;; *) echo "  FAIL: ${pair%%=*} should be ${pair#*=}"; ok=0 ;; esac
  done
  [ "$ok" = 1 ]
}
{
  status=0
  for round in 1 2 3; do
  echo "round $round"
  check https://example.com/ gpt=BLOCKED adsbygoogle=BLOCKED gtag=BLOCKED criteo=BLOCKED taboola=BLOCKED jquery=LOADED || status=1
  check https://www.accuweather.com/ gpt=LOADED adsbygoogle=BLOCKED jquery=LOADED || status=1
  done
  [ "$status" = 0 ] && echo "PASS" || echo "FAIL"
} > results/exceptions.txt 2>&1
cat results/exceptions.txt
grep -q '^PASS' results/exceptions.txt

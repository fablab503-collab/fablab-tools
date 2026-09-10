#!/usr/bin/env bash
# Burn test: hammer VeloTrack on an attached device or emulator and report what actually happened.
#
# Written to be run from a normal terminal. It cannot run from inside the Claude Code sandbox,
# which blocks every localhost socket, so adb cannot even reach its own server there.
#
#   tools/burn-test.sh                 full run (about 12 minutes)
#   tools/burn-test.sh --quick         skip the long monkey pass
#   tools/burn-test.sh --package X     test a different package id
#   tools/burn-test.sh --device X      which device to burn (or set ANDROID_SERIAL)
#
# Pass 4 throws 6000 random taps at the app, so the device is never guessed. With more than
# one attached the script stops and asks: picking the first line of "adb devices" once aimed
# a monkey run at the rider's own phone, which was sitting there over USB.
#
# Exit status is 0 only when nothing crashed, nothing ANR'd, and no OutOfMemoryError appeared.

set -uo pipefail

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$SDK/platform-tools/adb"
PKG="com.fablab503.velotrack"
QUICK=0
DEV="${ANDROID_SERIAL:-}"
OUT="${TMPDIR:-/tmp}/velotrack-burn"
FAILURES=0

while [ $# -gt 0 ]; do
  case "$1" in
    --quick) QUICK=1; shift ;;
    --package) PKG="$2"; shift 2 ;;
    --device) DEV="$2"; shift 2 ;;
    *) echo "unknown option: $1"; exit 2 ;;
  esac
done

mkdir -p "$OUT"
say()  { printf '\n==> %s\n' "$*"; }
fail() { printf '!!! %s\n' "$*"; FAILURES=$((FAILURES+1)); }
ok()   { printf '    ok: %s\n' "$*"; }

[ -x "$ADB" ] || { echo "adb not found at $ADB"; exit 2; }
ATTACHED="$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')"
COUNT="$(printf '%s' "$ATTACHED" | grep -c . )"
if [ -n "$DEV" ]; then
  printf '%s\n' "$ATTACHED" | grep -qx -- "$DEV" || {
    echo "$DEV is not attached. Attached now:"; printf '%s\n' "$ATTACHED" | sed 's/^/  /'; exit 2; }
elif [ "$COUNT" -eq 1 ]; then
  DEV="$ATTACHED"
elif [ "$COUNT" -eq 0 ]; then
  echo "No device. Start the emulator or plug the phone in."; exit 2
else
  echo "More than one device is attached, and pass 4 is 6000 random taps."
  echo "Say which one with --device <serial>:"
  "$ADB" devices -l | awk 'NR>1 && $2=="device"' | sed 's/^/  /'
  exit 2
fi
echo "device:  $DEV"
echo "package: $PKG"
"$ADB" -s "$DEV" shell dumpsys package "$PKG" | grep -E "versionName|versionCode" | head -2 | sed 's/^/         /'
"$ADB" -s "$DEV" shell pm list packages | grep -q "^package:$PKG$" || { echo "$PKG is not installed"; exit 2; }

# A burn test that reads yesterday's crash is worthless.
"$ADB" -s "$DEV" logcat -b crash -c 2>/dev/null
"$ADB" -s "$DEV" logcat -c 2>/dev/null

crashes() { "$ADB" -s "$DEV" logcat -b crash -d 2>/dev/null | grep -c "$PKG" || true; }

say "1/5  Cold start"
"$ADB" -s "$DEV" shell am force-stop "$PKG"
"$ADB" -s "$DEV" shell am start -W -n "$PKG/com.fablab503.velotrack.ui.MainActivity" > "$OUT/start.txt" 2>&1
grep -E "TotalTime|Status" "$OUT/start.txt" | sed 's/^/    /'
grep -q "Status: ok" "$OUT/start.txt" && ok "launched" || fail "launch failed - see $OUT/start.txt"

say "2/5  Memory while the biggest plan this app can ask for runs"
# The 300,000-cell planning limit exists because a whole country at z13-15 once exhausted this
# process's heap. The split is meant to keep every piece under it; this watches whether it does.
# DownloadMapActivity is not exported, so adb cannot open it: android:exported="false" makes
# "am start" a SecurityException. Nothing is going to open that screen but a person.
echo "    Open ⋮ → Download map → Choose a country → France → Fully detailed → Whole country now."
echo "    Watching the :download process for two minutes..."
sleep 3
PEAK=0
for i in $(seq 1 40); do
  KB="$("$ADB" -s "$DEV" shell dumpsys meminfo "$PKG:download" 2>/dev/null \
        | awk '/TOTAL PSS:/ {print $3; exit}')"
  [ -n "${KB:-}" ] && [ "$KB" -gt "$PEAK" ] 2>/dev/null && PEAK="$KB"
  sleep 3
done
if [ "$PEAK" -gt 0 ]; then
  echo "    peak PSS of the :download process: $((PEAK/1024)) MB"
  [ "$PEAK" -lt 400000 ] && ok "stayed under 400 MB" || fail "peak $((PEAK/1024)) MB - close to an OOM"
else
  echo "    (:download process never started - no download was running during this window)"
fi

say "3/5  Multi-part download: does it get past part 1?"
echo "    Start France + Detailed + Whole country on the device now, then leave it."
echo "    Watching the progress broadcasts for 4 minutes..."
"$ADB" -s "$DEV" logcat -c
( "$ADB" -s "$DEV" logcat -v time > "$OUT/parts.log" 2>&1 & echo $! > "$OUT/logcat.pid" )
sleep 240
kill "$(cat "$OUT/logcat.pid")" 2>/dev/null
PARTS="$(grep -c "PART_INDEX" "$OUT/parts.log" 2>/dev/null || true)"
echo "    progress lines mentioning a part index: ${PARTS:-0}"
echo "    (full log: $OUT/parts.log)"

say "4/5  Monkey stress"
EVENTS=6000; [ "$QUICK" = 1 ] && EVENTS=1200
"$ADB" -s "$DEV" shell monkey -p "$PKG" --throttle 40 --pct-syskeys 0 --ignore-timeouts \
  --ignore-security-exceptions -s 20260910 -v "$EVENTS" > "$OUT/monkey.txt" 2>&1
grep -E "Events injected|Monkey aborted|// (CRASH|NOT RESPONDING)" "$OUT/monkey.txt" | sed 's/^/    /'
grep -q "Monkey finished" "$OUT/monkey.txt" && ok "monkey completed" || fail "monkey aborted - see $OUT/monkey.txt"

say "5/5  What broke"
"$ADB" -s "$DEV" logcat -b crash -d > "$OUT/crash.log" 2>/dev/null
C="$(crashes)"
[ "${C:-0}" -eq 0 ] && ok "crash buffer clean" || fail "$C crash lines - see $OUT/crash.log"
"$ADB" -s "$DEV" logcat -d 2>/dev/null | grep -i "OutOfMemoryError" > "$OUT/oom.log" || true
[ -s "$OUT/oom.log" ] && fail "OutOfMemoryError - see $OUT/oom.log" || ok "no OutOfMemoryError"
"$ADB" -s "$DEV" logcat -d 2>/dev/null | grep -i "ANR in $PKG" > "$OUT/anr.log" || true
[ -s "$OUT/anr.log" ] && fail "ANR - see $OUT/anr.log" || ok "no ANR"

printf '\n=====================================\n'
if [ "$FAILURES" -eq 0 ]; then
  echo "PASS - nothing crashed, hung or ran out of memory"
else
  echo "$FAILURES PROBLEM(S) - logs in $OUT"
fi
echo "====================================="
exit "$FAILURES"

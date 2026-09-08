#!/bin/bash
#
# Emulator verification helpers, written while testing VeloTrack's route following, off-route
# alerts and place picker on 2026-09-08. Source this, or copy the function you need.
#
# Why these exist:
#   * adb cannot run from Claude's sandbox at all. Every call has to go through the unsandboxed
#     "Control your Mac" osascript tool, which kills anything over ~30 s, so long sequences must be
#     detached with nohup and waited on through a log or a .done file.
#   * With a real phone plugged in as well as the emulator, a bare adb call fails with "more than
#     one device/emulator". Always pass -s.
#   * Emulator fixes carry +/-5 m accuracy and the app derives speed from displacement, so anything
#     slower than about 20 km/h reads as standing still. Do not test speed-driven features at 6 km/h.
#
# Usage from osascript, always detached:
#   do shell script "nohup /path/to/emulator-verify.sh ride 20 45 48.8566 2.3522 >/dev/null 2>&1 &"
# then poll for /tmp/claude/emu/<name>.done from the sandbox.

set -uo pipefail

SDK="${ANDROID_SDK:-$HOME/Library/Android/sdk}"
SERIAL="${EMU_SERIAL:-emulator-5554}"
ADB="$SDK/platform-tools/adb -s $SERIAL"
OUT="${EMU_OUT:-/tmp/claude/emu}"
mkdir -p "$OUT"

# Screenshot to $OUT/<name>.png. Read it back from the sandbox; /tmp/claude is readable there.
shot() {
    $ADB exec-out screencap -p > "$OUT/$1.png" 2>/dev/null
    echo "$1 $(stat -f %z "$OUT/$1.png" 2>/dev/null || echo 0) bytes"
}

# Ride east in a straight line at a constant speed, one GPS fix per second.
# ride <kmh> <seconds> [lat] [lon]
ride() {
    local kmh=${1:-20} secs=${2:-30} lat=${3:-48.8566} lon0=${4:-2.3522}
    local dlon
    dlon=$(python3 -c "import math; print($kmh/3.6/(111320*math.cos(math.radians($lat))))")
    rm -f "$OUT/ride.done"
    for i in $(seq 0 "$secs"); do
        $ADB emu geo fix "$(python3 -c "print(round($lon0 + $i * $dlon, 7))")" "$lat" 30 >/dev/null 2>&1
        sleep 1
    done
    echo "rode $kmh km/h for $secs s from $lat,$lon0" > "$OUT/ride.done"
}

# Drift perpendicular to that line and then HOLD position there.
# Holding is the point: a rule with a delay in it (off-route after 10 s beyond 50 m) only trips if
# fixes keep arriving from the same wrong place. A single jump proves nothing.
# drift <metres> <hold seconds> <lon> [lat]
drift() {
    local metres=${1:-160} hold=${2:-22} lon=${3:?lon required} lat0=${4:-48.8566}
    rm -f "$OUT/drift.done"
    local steps=8
    for i in $(seq 1 $steps); do
        $ADB emu geo fix "$lon" \
            "$(python3 -c "print(round($lat0 + $i * $metres/$steps/111320, 7))")" 30 >/dev/null 2>&1
        sleep 1
    done
    local off
    off=$(python3 -c "print(round($lat0 + $metres/111320, 7))")
    for _ in $(seq 1 "$hold"); do
        $ADB emu geo fix "$lon" "$off" 30 >/dev/null 2>&1
        sleep 1
    done
    echo "held $metres m off route at $off" > "$OUT/drift.done"
}

# Install a downloaded APK and report the version that ended up on the device.
install_apk() {
    local apk=${1:?apk path required}
    rm -f "$OUT/install.done"
    $ADB install -r "$apk"
    $ADB shell dumpsys package com.fablab503.velotrack | grep versionName | head -1
    echo DONE > "$OUT/install.done"
}

# Put a file where the system document picker will offer it immediately (GPX import, etc).
push_document() {
    local file=${1:?file required}
    $ADB push "$file" "/sdcard/Download/$(basename "$file")"
    $ADB shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
        -d "file:///sdcard/Download/$(basename "$file")" >/dev/null
}

# Restart the app at a chosen position.
restart_at() {
    local lat=${1:-48.8566} lon=${2:-2.3522}
    $ADB shell am force-stop com.fablab503.velotrack
    $ADB emu geo fix "$lon" "$lat" 35 >/dev/null 2>&1
    $ADB shell am start -n com.fablab503.velotrack/.ui.MainActivity >/dev/null 2>&1
}

# Dispatch so the file works as a command as well as a library.
if [ "${BASH_SOURCE[0]}" = "$0" ] && [ $# -gt 0 ]; then
    cmd=$1
    shift
    "$cmd" "$@"
fi
exit 0

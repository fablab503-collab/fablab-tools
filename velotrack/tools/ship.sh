#!/usr/bin/env bash
# Build VeloTrack on this Mac, put it on every attached device, and prove it started.
#
# Why this exists: every build used to be a round trip through GitHub Actions — push, wait three
# minutes, download an APK — and the result could not be installed on a phone that already had the
# app from Google Play, because Play App Signing re-signs the release with Google's key. This
# script closes both gaps. It builds the *debug* variant, whose applicationId carries a `.debug`
# suffix (app/build.gradle.kts), so it installs **alongside** the Play copy: two icons, two data
# directories, no signature clash, and the rider's real rides are never touched.
#
#   tools/ship.sh                 build, install and verify on every attached device
#   tools/ship.sh --build-only    build, do not touch any device
#   tools/ship.sh --device SERIAL only this device
#   tools/ship.sh --play FILE.apk skip the build; install a Play-signed APK downloaded from the
#                                 Play Console (App bundle explorer -> Downloads -> Signed,
#                                 universal APK). That one *replaces* the rider's Play copy,
#                                 in place and with its data intact, because it carries Google's
#                                 signature. Use it to hand the rider a build without asking them
#                                 to tap "Update" in the Play Store.
#
# Everything is asserted, not merely logged: a device that is asleep, locked or missing fails the
# run rather than producing an empty screenshot that reads like a pass.
#
# Requires an unsandboxed shell (Gradle binds a local socket for its file locks, and adb needs to
# start its own server); run it detached and read $LOG rather than expecting it inline.

set -uo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLCHAIN="${VELOTRACK_TOOLCHAIN:-$HOME/Documents/GitHub/.toolchain}"
GRADLE="${VELOTRACK_GRADLE:-$TOOLCHAIN/gradle-9.7.1/bin/gradle}"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$SDK/platform-tools/adb"
OUT="${VELOTRACK_OUT:-/tmp/claude/emu}"
LOG="$OUT/ship.log"
PKG_DEBUG="com.fablab503.velotrack.debug"
PKG_RELEASE="com.fablab503.velotrack"
ACTIVITY="com.fablab503.velotrack.ui.MainActivity"

BUILD_ONLY=0
ONLY_DEVICE=""
PLAY_APK=""
while [ $# -gt 0 ]; do
    case "$1" in
        --build-only) BUILD_ONLY=1; shift ;;
        --device) ONLY_DEVICE="$2"; shift 2 ;;
        --play) PLAY_APK="$2"; shift 2 ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done

mkdir -p "$OUT"
: > "$LOG"
rm -f "$OUT/ship.done"
exec > >(tee -a "$LOG") 2>&1

FAILURES=0
say()  { printf '\n==> %s\n' "$*"; }
fail() { printf '!!! %s\n' "$*"; FAILURES=$((FAILURES + 1)); }

finish() {
    printf '\n=== %s ===\n' "$([ "$FAILURES" -eq 0 ] && echo 'ALL GREEN' || echo "$FAILURES FAILURE(S)")"
    echo DONE
    touch "$OUT/ship.done"
    exit "$([ "$FAILURES" -eq 0 ] && echo 0 || echo 1)"
}

# ---- build -----------------------------------------------------------------------------------

APK=""
if [ -n "$PLAY_APK" ]; then
    APK="$PLAY_APK"
    [ -f "$APK" ] || { fail "no such APK: $APK"; finish; }
    say "Using Play-signed APK $APK ($(du -h "$APK" | cut -f1))"
else
    # Android Studio ships the only JDK on this machine; /usr/bin/java is a stub that has no runtime.
    export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
    export PATH="$JAVA_HOME/bin:/opt/homebrew/bin:$PATH"
    export ANDROID_HOME="$SDK"
    # Gradle unpacks a native library and takes file locks under GRADLE_USER_HOME; the default
    # ~/.gradle is not writable from this agent's sandbox, so keep it beside the distribution.
    export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$TOOLCHAIN/gradle-home}"

    [ -x "$GRADLE" ] || { fail "Gradle not found at $GRADLE — see docs/updates/README.md"; finish; }
    [ -d "$SDK/platforms" ] || { fail "Android SDK not found at $SDK"; finish; }
    echo "sdk.dir=$SDK" > "$APP_DIR/local.properties"   # gitignored; AGP needs it for a local build

    say "Building the map assets (skips anything already downloaded)"
    bash "$APP_DIR/tools/build-assets.sh" || fail "build-assets.sh failed"

    say "gradle assembleDebug"
    ( cd "$APP_DIR" && "$GRADLE" --no-daemon assembleDebug ) || { fail "build failed"; finish; }

    APK="$APP_DIR/app/build/outputs/apk/debug/app-debug.apk"
    [ -f "$APK" ] || { fail "gradle reported success but $APK is missing"; finish; }
    say "Built $(basename "$APK") ($(du -h "$APK" | cut -f1))"
fi

VERSION="$("$SDK"/build-tools/*/aapt2 dump badging "$APK" 2>/dev/null |
           sed -n "1s/.*versionName='\([^']*\)'.*/\1/p")"
PKG="$("$SDK"/build-tools/*/aapt2 dump badging "$APK" 2>/dev/null |
       sed -n "1s/.*package: name='\([^']*\)'.*/\1/p")"
echo "package=$PKG version=$VERSION"
[ -n "$PKG" ] || { fail "could not read the package name out of the APK"; finish; }

[ "$BUILD_ONLY" -eq 1 ] && finish

# ---- devices ---------------------------------------------------------------------------------

"$ADB" start-server >/dev/null 2>&1
DEVICES="$("$ADB" devices | awk 'NR>1 && $2=="device"{print $1}')"
[ -n "$ONLY_DEVICE" ] && DEVICES="$ONLY_DEVICE"
if [ -z "$DEVICES" ]; then
    fail "no device attached — plug the phone in, or start the emulator"
    finish
fi

for S in $DEVICES; do
    say "Device $S — $("$ADB" -s "$S" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"

    # Preflight. Each of these has already cost a wasted run at least once: a phone that had been
    # unplugged produced an empty log, and a sleeping phone produced a zero-byte screenshot.
    [ "$("$ADB" -s "$S" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] \
        || { fail "$S is not finished booting"; continue; }
    "$ADB" -s "$S" shell input keyevent 224 >/dev/null 2>&1   # WAKEUP is idempotent; POWER toggles
    sleep 1
    if "$ADB" -s "$S" shell dumpsys window 2>/dev/null | grep -q 'mShowingLockscreen=true'; then
        fail "$S is locked — a PIN or pattern has to be entered by hand, never over adb"
        continue
    fi

    say "Installing"
    INSTALL_OUT="$("$ADB" -s "$S" install -r "$APK" 2>&1)"
    echo "$INSTALL_OUT"
    if echo "$INSTALL_OUT" | grep -q 'INSTALL_FAILED_USER_RESTRICTED'; then
        fail "$S refuses installs over USB. On MIUI/HyperOS: Settings -> Additional settings ->
      Developer options -> turn ON 'Install via USB' (and 'USB debugging (Security settings)').
      This is a device security switch, so it has to be flipped on the phone itself."
        continue
    fi
    if echo "$INSTALL_OUT" | grep -q 'INSTALL_FAILED_UPDATE_INCOMPATIBLE'; then
        fail "$S already has a copy of $PKG signed with a different key. The Play copy can only be
      replaced by a Play-signed APK: Play Console -> App bundle explorer -> Downloads ->
      'Signed, universal APK', then tools/ship.sh --play <that file>."
        continue
    fi
    echo "$INSTALL_OUT" | grep -q 'Success' || { fail "install failed on $S"; continue; }

    # Location is the whole point of the app; grant it BEFORE the first launch, or the screenshot
    # photographs the system permission sheet instead of the app. Silent if already answered.
    for P in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION POST_NOTIFICATIONS; do
        "$ADB" -s "$S" shell pm grant "$PKG" "android.permission.$P" >/dev/null 2>&1
    done

    # Marker-based crash detection: `logcat -c` would also hide a crash that happened *before* the
    # clear, so note where the buffer ends and read only what arrives after. The whole date command
    # has to be quoted as one word — the remote shell splits on the space otherwise, and toybox
    # date then reports "Max 1 argument", leaving the marker silently empty.
    MARK="$("$ADB" -s "$S" shell "date '+%m-%d %H:%M:%S.000'" | tr -d '\r')"
    [ -n "$MARK" ] || fail "could not read the clock on $S; crash detection will cover the whole buffer"

    say "Launching"
    START_OUT="$("$ADB" -s "$S" shell am start -W -n "$PKG/$ACTIVITY" 2>&1)"
    echo "$START_OUT"
    echo "$START_OUT" | grep -q 'Status: ok' || fail "$PKG/$ACTIVITY did not start on $S"
    sleep 6   # the AVD renders the map in software; anything less photographs a grey screen

    SHOT="$OUT/ship_${S}.png"
    "$ADB" -s "$S" exec-out screencap -p > "$SHOT"
    if [ ! -s "$SHOT" ]; then
        fail "screenshot from $S is empty (screen off, or a secure window is in front)"
    else
        echo "screenshot: $SHOT ($(du -h "$SHOT" | cut -f1))"
    fi

    say "Crash buffer since $MARK"
    CRASH="$("$ADB" -s "$S" logcat -d -b crash -t "$MARK" 2>/dev/null | grep -i velotrack)"
    if [ -n "$CRASH" ]; then
        echo "$CRASH"
        fail "$PKG crashed on $S"
    else
        echo "clean"
    fi

    PID="$("$ADB" -s "$S" shell pidof "$PKG" | tr -d '\r')"
    [ -n "$PID" ] || fail "$PKG is not running on $S after launch"
    echo "pid=$PID"
done

finish

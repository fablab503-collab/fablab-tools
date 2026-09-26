#!/bin/bash
ADB=/Users/danielmadac/Library/Android/sdk/platform-tools/adb
OUT=/tmp/claude-501/emu
N=${1:-30}; LAT0=${2:-37.4220}; LON=${3:--122.0840}; STEP=${4:-0.00005}
for i in $(seq 1 "$N"); do
  lat=$(python3 -c "print(round($LAT0 + $i * $STEP, 6))")
  "$ADB" emu geo fix "$LON" "$lat" 30 >/dev/null 2>&1
  sleep 1
done
sleep 1
"$ADB" shell screencap -p /sdcard/velo.png
"$ADB" pull /sdcard/velo.png "$OUT/velo4.png" >/dev/null 2>&1
echo "--- notif"
"$ADB" shell dumpsys notification --noredact 2>/dev/null | grep -i 'velotrack' | head -n 3
echo "--- logcat errors"
"$ADB" logcat -d 2>/dev/null | grep -E 'AndroidRuntime|FATAL|velotrack' | grep -E ' E/| F/| E | F |Exception|FATAL' | tail -n 15
exit 0

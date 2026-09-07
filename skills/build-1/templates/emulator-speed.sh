#!/bin/bash
# speed.sh <kmh> <seconds> [lat0] [lon0]  — emits one GPS fix per second moving east at <kmh>.
ADB=/Users/danielmadac/Library/Android/sdk/platform-tools/adb
KMH=${1:-6}; SECS=${2:-20}; LAT=${3:-37.4230}; LON0=${4:--122.0840}
DLON=$(python3 -c "import math; print($KMH/3.6/(111320*math.cos(math.radians($LAT))))")
for i in $(seq 0 "$SECS"); do
  lon=$(python3 -c "print(round($LON0 + $i * $DLON, 7))")
  "$ADB" emu geo fix "$lon" "$LAT" 30 >/dev/null 2>&1
  sleep 1
done
echo "done $KMH km/h for $SECS s" > /tmp/claude-501/emu/speed.done
exit 0

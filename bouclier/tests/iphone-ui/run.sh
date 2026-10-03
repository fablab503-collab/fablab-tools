#!/bin/bash
# Bouclier on simulated iPhones: runs the UI test (UITests/BouclierOnIPhone.swift) on each simulator given,
# one after the other, and exports its screenshots and screen trees.
#
# usage: tests/iphone-ui/run.sh <simulator udid> [<udid> ...]
#   needs Bouclier built for the simulator first (SIM_ID=<udid> ./build.sh --sim) and XcodeGen
#   out/<simulator name><TAG>/: <step>.png, <step>-tree.txt, test.log, result.xcresult
# options (environment): APP=<another Bouclier.app>  TAG=<suffix for the out folder>
#   FRESH=1 removes Bouclier from the simulator first (Safari forgets it: off, no website access)
#   ONLY=<test name> runs one step, for example ONLY=test2_TurnOnInSettings
set -uo pipefail
cd "$(dirname "$0")"
export PATH=/opt/homebrew/bin:/usr/local/bin:$PATH
[ -d BouclierIPhoneTests.xcodeproj ] || xcodegen generate --quiet || exit 1
APP="${APP:-$(ls -d ../../build/DerivedData/Build/Products/Debug-iphonesimulator/Bouclier.app 2>/dev/null | head -1)}"
[ -n "$APP" ] || { echo "Build Bouclier for the simulator first: SIM_ID=<udid> ./build.sh --sim"; exit 1; }
for U in "$@"; do
  NAME=$(xcrun simctl list devices -j | /usr/bin/python3 -c 'import json,sys; u=sys.argv[1]; print(next(d["name"] for ds in json.load(sys.stdin)["devices"].values() for d in ds if d["udid"] == u))' "$U")
  OUT="out/$NAME${TAG:-}"
  rm -rf "$OUT"; mkdir -p "$OUT"
  xcrun simctl boot "$U" 2>/dev/null
  xcrun simctl bootstatus "$U" -b > /dev/null
  [ "${FRESH:-0}" = 1 ] && xcrun simctl uninstall "$U" com.danielmadac.Bouclier
  xcrun simctl install "$U" "$APP"
  # no diagnostics collection after a failure: "simctl diagnose" hung for 10 minutes (3 Oct 2026), and
  # stopping it crashed xcodebuild before it wrote the screenshots
  xcodebuild test -project BouclierIPhoneTests.xcodeproj -scheme BouclierIPhoneTests -destination "id=$U" \
    -derivedDataPath build/dd -resultBundlePath "$OUT/result.xcresult" -collect-test-diagnostics never \
    ${ONLY:+-only-testing:BouclierUITests/BouclierOnIPhone/$ONLY} > "$OUT/test.log" 2>&1
  echo "$NAME: test exit $?"
  xcrun xcresulttool export attachments --path "$OUT/result.xcresult" --output-path "$OUT/attachments" > /dev/null 2>&1
  /usr/bin/python3 - "$OUT" <<'PY'
import json, os, shutil, sys
out = sys.argv[1]
att = os.path.join(out, "attachments")
try:
    manifest = json.load(open(os.path.join(att, "manifest.json")))
except Exception as e:
    print("  no attachments:", e)
    sys.exit(0)
n = 0
for test in manifest:
    for a in test.get("attachments", []):
        name = a.get("suggestedHumanReadableName") or a["exportedFileName"]
        base, ext = os.path.splitext(a["exportedFileName"])
        clean = name.split("_")[0] if "_" in name else os.path.splitext(name)[0]
        shutil.copy(os.path.join(att, a["exportedFileName"]), os.path.join(out, clean + (ext or ".txt")))
        n += 1
print(f"  {n} attachments")
PY
  grep -E "Test Case .*(passed|failed)|error: -|XCTAssert" "$OUT/test.log" | sed 's|^|  |' | head -20
  xcrun simctl shutdown "$U" 2>/dev/null
done

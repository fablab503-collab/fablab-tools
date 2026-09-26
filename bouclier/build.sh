#!/bin/bash
# Bouclier build script (macOS).
#
#   ./build.sh             download fresh filter lists, compile the Safari rules,
#                          build the Mac app and install it in /Applications
#   ./build.sh --lists     only refresh lists and rules in extension/ (enough for
#                          Safari's "Add Temporary Extension…", no Xcode needed)
#   ./build.sh --app       build and install the Mac app from the rules already in extension/
#   ./build.sh --sim       build the iPhone/iPad app and run it in the iOS Simulator
#   ./build.sh --release   fresh lists, then archive the Mac and iPhone/iPad apps and upload
#                          both builds to App Store Connect (one app record, universal purchase)
#       --no-lists           skip the list refresh (use the rules already in extension/)
#       --no-upload          archive and export to build/export/ without uploading
#       --png-icon           use the flat PNG icons instead of appstore/AppIcon.icon
#
# Needs: python3 (ships with macOS). For the apps: Xcode 26 or later, signed in with the Apple ID
# of the Apple Developer Program membership (Xcode > Settings > Accounts). TEAM_ID=XXXXXXXXXX
# picks a team; ASC_KEY_PATH, ASC_KEY_ID and ASC_ISSUER_ID use an App Store Connect API key for
# the upload instead of the Xcode account.
set -euo pipefail
cd "$(dirname "$0")"

APP_NAME="Bouclier"
BUNDLE_ID="com.danielmadac.Bouclier"
MODE="all"
LISTS=1
UPLOAD=1
ICON_FLAG=""
for arg in "$@"; do
  case "$arg" in
    --lists|--app|--sim|--release) MODE="$arg" ;;
    --no-lists) LISTS=0 ;;
    --no-upload) UPLOAD=0 ;;
    --png-icon) ICON_FLAG="--png-icon" ;;
    *) echo "Unknown option: $arg (see the top of build.sh)"; exit 1 ;;
  esac
done
if [ "$MODE" = "--app" ] || [ "$MODE" = "--sim" ]; then LISTS=0; fi

step() { printf '\n\033[1m▸ %s\033[0m\n' "$*"; }

if [ "$LISTS" = 1 ]; then
  step "Downloading filter lists"
  python3 tools/fetch_lists.py --cache lists-cache
  step "Compiling Safari rules"
  python3 tools/convert.py --cache lists-cache --out extension
fi
[ "$MODE" = "--lists" ] && { echo; echo "Rules updated in $(pwd)/extension"; exit 0; }

# App Store Connect rejects the upload when the extension's description is longer than 112 characters
python3 - <<'PY' || exit 1
import json, sys
d = json.load(open("extension/manifest.json")).get("description", "")
if not isinstance(d, str) or not d or len(d) > 112:
    sys.exit(f"extension/manifest.json: the description must be 1-112 characters for the App Store (it has {len(d)})")
PY

step "Finding Xcode"
if [ -z "${DEVELOPER_DIR:-}" ]; then
  if [[ "$(xcode-select -p 2>/dev/null)" == *CommandLineTools* ]] || ! xcrun --find xcodebuild >/dev/null 2>&1; then
    XCODE_APP="$(ls -d /Applications/Xcode*.app 2>/dev/null | sort | tail -1 || true)"
    [ -n "$XCODE_APP" ] || { echo "Xcode is not installed. Install it from the App Store, or use ./build.sh --lists"; exit 1; }
    export DEVELOPER_DIR="$XCODE_APP/Contents/Developer"
  fi
fi
xcodebuild -version | sed -n 1p   # sed reads everything: head would cut the pipe and trip pipefail

if [ -z "${TEAM_ID:-}" ]; then
  TEAM_ID="$(defaults export com.apple.dt.Xcode - 2>/dev/null | python3 -c '
import plistlib, sys
try:
    prefs = plistlib.loads(sys.stdin.buffer.read())  # Xcode prefs hold dates and data, which JSON cannot
except Exception:
    sys.exit(0)
teams = []
for key in ("IDEProvisioningTeamByIdentifier", "IDEProvisioningTeams"):
    for entries in (prefs.get(key) or {}).values():
        teams.extend(entries)
teams.sort(key=lambda t: t.get("isFreeProvisioningTeam", False))  # paid team first
print(teams[0]["teamID"] if teams else "")
' || true)"
fi
if [ -z "$TEAM_ID" ]; then
  echo "No Apple ID found in Xcode. Open Xcode > Settings > Accounts, add your Apple ID, then run this again."
  exit 1
fi
echo "Signing team: $TEAM_ID"

if [ "$MODE" = "--sim" ] || [ "$MODE" = "--release" ]; then
  if ! xcodebuild -showsdks 2>/dev/null | grep -q iphoneos; then
    step "Downloading the iOS platform for Xcode (several GB, once)"
    xcodebuild -downloadPlatform iOS
  fi
fi

step "Generating the Xcode project (Mac, iPhone and iPad)"
rm -rf build/xcode
TOOL=""
for candidate in safari-web-extension-converter safari-web-extension-packager; do
  if xcrun --find "$candidate" >/dev/null 2>&1; then TOOL="$candidate"; break; fi
done
[ -n "$TOOL" ] || { echo "Neither safari-web-extension-packager nor -converter is available in this Xcode"; exit 1; }
xcrun "$TOOL" extension \
  --project-location build/xcode --app-name "$APP_NAME" --bundle-identifier "$BUNDLE_ID" \
  --swift --copy-resources --no-open --no-prompt --force
PROJECT="$(find build/xcode -maxdepth 3 -name '*.xcodeproj' | sed -n 1p)"
[ -n "$PROJECT" ] || { echo "The Xcode project was not generated"; exit 1; }
python3 tools/xcode_setup.py build/xcode $ICON_FLAG

scheme_for() {  # the app scheme for a platform: macOS or iOS
  xcodebuild -list -json -project "$PROJECT" | python3 -c '
import json, sys
schemes = json.load(sys.stdin)["project"]["schemes"]
apps = [s for s in schemes if "Extension" not in s]
pick = [s for s in apps if sys.argv[1] in s] or apps or schemes
print(pick[0])
' "$1"
}
SCHEME_MAC="$(scheme_for macOS)"
SCHEME_IOS="$(scheme_for iOS)"
echo "Project: $PROJECT (schemes: $SCHEME_MAC, $SCHEME_IOS)"

BUILD_NUMBER="$(date +%Y%m%d%H%M)"
VERSION="$(python3 -c 'import json; print(json.load(open("extension/manifest.json"))["version"])')"
SETTINGS=(
  DEVELOPMENT_TEAM="$TEAM_ID" CODE_SIGN_STYLE=Automatic
  MARKETING_VERSION="$VERSION" CURRENT_PROJECT_VERSION="$BUILD_NUMBER"
  MACOSX_DEPLOYMENT_TARGET=26.0 IPHONEOS_DEPLOYMENT_TARGET=26.0
  INFOPLIST_KEY_ITSAppUsesNonExemptEncryption=NO
  INFOPLIST_KEY_LSApplicationCategoryType=public.app-category.utilities
  INFOPLIST_KEY_CFBundleDisplayName="$APP_NAME"
)
AUTH=(-allowProvisioningUpdates)
if [ -n "${ASC_KEY_PATH:-}" ]; then
  AUTH+=(-authenticationKeyPath "$ASC_KEY_PATH" -authenticationKeyID "$ASC_KEY_ID" -authenticationKeyIssuerID "$ASC_ISSUER_ID")
fi

# macOS registers every app it sees, so the copies under build/ (DerivedData, archives, exports)
# would show up as a second Bouclier in Safari > Settings > Extensions. Only /Applications counts.
LSREGISTER=/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister
forget_build_copies() {
  find "$PWD/build" -name "$APP_NAME.app" -type d -prune 2>/dev/null | while IFS= read -r copy; do
    for appex in "$copy"/Contents/PlugIns/*.appex; do
      [ -d "$appex" ] && pluginkit -r "$appex" >/dev/null 2>&1 || true
    done
    "$LSREGISTER" -u "$copy" >/dev/null 2>&1 || true
  done
}

if [ "$MODE" = "--sim" ]; then
  step "Building for the iOS Simulator"
  SIM_ID="$(xcrun simctl list devices available -j | python3 -c '
import json, sys
devices = json.load(sys.stdin)["devices"]
phones = [(runtime, d) for runtime, ds in devices.items() if "iOS" in runtime for d in ds if d["name"].startswith("iPhone")]
booted = [d for _, d in phones if d["state"] == "Booted"]
newest = [d for _, d in sorted(phones, key=lambda p: p[0], reverse=True)]
print((booted or newest)[0]["udid"] if phones else "")
')"
  [ -n "$SIM_ID" ] || { echo "No iPhone simulator found. Xcode > Settings > Components adds one."; exit 1; }
  xcrun simctl boot "$SIM_ID" 2>/dev/null || true
  DEV_DIR="${DEVELOPER_DIR:-$(xcode-select -p 2>/dev/null)}"
  for sim_app in "$DEV_DIR/../Applications/DeviceHub.app" "$DEV_DIR/Applications/Simulator.app"; do  # Xcode 27 renamed Simulator to DeviceHub
    [ -d "$sim_app" ] && { open "$sim_app" || true; break; }
  done
  xcodebuild -project "$PROJECT" -scheme "$SCHEME_IOS" -configuration Debug \
    -destination "id=$SIM_ID" -derivedDataPath build/DerivedData "${AUTH[@]}" "${SETTINGS[@]}" build -quiet
  SIM_APP="$(find build/DerivedData/Build/Products/Debug-iphonesimulator -maxdepth 1 -name '*.app' | sed -n 1p)"
  xcrun simctl install "$SIM_ID" "$SIM_APP"
  xcrun simctl launch "$SIM_ID" "$BUNDLE_ID" >/dev/null
  echo
  echo "Running in the Simulator. In the simulated iPhone: Settings > Apps > Safari > Extensions > Bouclier > on, All Websites > Allow."
  exit 0
fi

if [ "$MODE" = "--release" ]; then
  ARCHIVES="build/archives"
  rm -rf "$ARCHIVES" build/export
  step "Archiving the Mac app ($VERSION, build $BUILD_NUMBER)"
  xcodebuild -project "$PROJECT" -scheme "$SCHEME_MAC" -configuration Release \
    -destination 'generic/platform=macOS' -archivePath "$ARCHIVES/$APP_NAME-macOS.xcarchive" \
    "${AUTH[@]}" "${SETTINGS[@]}" archive -quiet
  step "Archiving the iPhone and iPad app ($VERSION, build $BUILD_NUMBER)"
  xcodebuild -project "$PROJECT" -scheme "$SCHEME_IOS" -configuration Release \
    -destination 'generic/platform=iOS' -archivePath "$ARCHIVES/$APP_NAME-iOS.xcarchive" \
    "${AUTH[@]}" "${SETTINGS[@]}" TARGETED_DEVICE_FAMILY=1,2 archive -quiet

  step "Checking the archives"
  MAC_APP="$ARCHIVES/$APP_NAME-macOS.xcarchive/Products/Applications/$APP_NAME.app"
  ENTITLEMENTS="$(codesign -d --entitlements - --xml "$MAC_APP" 2>/dev/null || true)"
  case "$ENTITLEMENTS" in
    *app-sandbox*) echo "Mac app is sandboxed" ;;
    *) echo "The Mac app is not sandboxed: the Mac App Store would refuse it"; exit 1 ;;
  esac
  for archive in "$ARCHIVES"/*.xcarchive; do
    /usr/libexec/PlistBuddy -c 'Print :ApplicationProperties:CFBundleShortVersionString' "$archive/Info.plist" | sed "s|^|$(basename "$archive"): version |"
  done

  DESTINATION="upload"; [ "$UPLOAD" = 1 ] || DESTINATION="export"
  cat > build/ExportOptions.plist <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>method</key><string>app-store-connect</string>
  <key>destination</key><string>$DESTINATION</string>
  <key>teamID</key><string>$TEAM_ID</string>
  <key>signingStyle</key><string>automatic</string>
  <key>uploadSymbols</key><true/>
  <key>manageAppVersionAndBuildNumber</key><false/>
</dict>
</plist>
PLIST
  for platform in macOS iOS; do
    step "$([ "$UPLOAD" = 1 ] && echo Uploading || echo Exporting) the $platform build"
    xcodebuild -exportArchive -archivePath "$ARCHIVES/$APP_NAME-$platform.xcarchive" \
      -exportOptionsPlist build/ExportOptions.plist -exportPath "build/export/$platform" "${AUTH[@]}"
  done
  forget_build_copies
  echo
  if [ "$UPLOAD" = 1 ]; then
    echo "Both builds are uploaded ($VERSION, build $BUILD_NUMBER). App Store Connect processes them in"
    echo "5-30 minutes; then pick them on the version page and submit for review (see appstore/README.md)."
  else
    echo "Exported to build/export/ (nothing uploaded)."
  fi
  exit 0
fi

step "Building the Mac app"
xcodebuild -project "$PROJECT" -scheme "$SCHEME_MAC" -configuration Release \
  -derivedDataPath build/DerivedData "${AUTH[@]}" "${SETTINGS[@]}" build -quiet
BUILT="build/DerivedData/Build/Products/Release/$APP_NAME.app"
[ -d "$BUILT" ] || { echo "Build output not found at $BUILT"; exit 1; }
codesign --verify --deep --strict "$BUILT" && echo "Signature OK"

step "Installing to /Applications"
osascript -e "tell application \"$APP_NAME\" to quit" >/dev/null 2>&1 || true
rm -rf "/Applications/$APP_NAME.app"
ditto "$BUILT" "/Applications/$APP_NAME.app"
forget_build_copies
"$LSREGISTER" -f "/Applications/$APP_NAME.app" >/dev/null 2>&1 || true
open "/Applications/$APP_NAME.app"
echo
echo "Installed. In Safari: Settings > Extensions > turn on $APP_NAME, then allow it on every website."

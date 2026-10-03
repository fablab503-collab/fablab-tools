#!/bin/bash
# Builds build/PopupShot.app and build/PermissionProbe (see PopupShot.swift, PermissionProbe.swift).
# PopupShot is an app bundle like BouclierBench: WebKit keeps compiled rules per app, and a bare
# command-line tool never got its rules compiled (a site pause then waited forever).
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build
APP=build/PopupShot.app
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS"
xcrun swiftc -O -swift-version 5 -target "$(uname -m)-apple-macos15.4" \
  -framework WebKit -framework AppKit PopupShot.swift -o "$APP/Contents/MacOS/PopupShot"
cat > "$APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleIdentifier</key><string>com.danielmadac.BouclierPopupShot</string>
  <key>CFBundleName</key><string>PopupShot</string>
  <key>CFBundleExecutable</key><string>PopupShot</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>1.0</string>
  <key>LSUIElement</key><true/>
  <key>NSPrincipalClass</key><string>NSApplication</string>
</dict></plist>
PLIST
codesign --force --sign - "$APP" >/dev/null
xcrun swiftc -O -swift-version 5 -target "$(uname -m)-apple-macos15.4" \
  -framework WebKit -framework AppKit PermissionProbe.swift -o build/PermissionProbe
echo "built $APP and build/PermissionProbe"

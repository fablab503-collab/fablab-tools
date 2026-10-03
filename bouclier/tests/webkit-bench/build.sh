#!/bin/bash
# Builds build/BouclierBench.app, the WebKit benchmark tool (see BouclierBench.swift).
set -euo pipefail
cd "$(dirname "$0")"
APP=build/BouclierBench.app
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS"
xcrun swiftc -O -swift-version 5 -target "$(uname -m)-apple-macos15.4" \
  -framework WebKit -framework AppKit BouclierBench.swift -o "$APP/Contents/MacOS/BouclierBench"
cat > "$APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleIdentifier</key><string>com.danielmadac.BouclierBench</string>
  <key>CFBundleName</key><string>BouclierBench</string>
  <key>CFBundleExecutable</key><string>BouclierBench</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>1.0</string>
  <key>LSUIElement</key><true/>
  <key>NSPrincipalClass</key><string>NSApplication</string>
</dict></plist>
PLIST
codesign --force --sign - "$APP" >/dev/null
echo "built $APP"

#!/bin/bash
# Builds Eco.app, one universal app for Apple silicon and Intel Macs.
#
#   ./build.sh             build build/Eco.app and build/Eco-mac.zip
#   ./build.sh --install   and copy the app to /Applications
#
# Needs Xcode or the Command Line Tools, and an internet connection (it downloads uv, the
# Python installer the app uses to set up its engine on first launch).
# Signing: by default the app gets an ad-hoc signature, which macOS only opens after
# "Open Anyway" in Privacy & Security. For an app macOS opens like any other, set
#   SIGN_IDENTITY="Developer ID Application: Your Name (TEAMID)"   signs with that certificate
#   NOTARY_KEY=path/to/AuthKey_XXXX.p8 NOTARY_KEY_ID=… NOTARY_ISSUER=…   and has Apple notarise it
# (an App Store Connect API key; the same kind Bouclier uses for its uploads).
set -euo pipefail
cd "$(dirname "$0")"

INSTALL=0
for arg in "$@"; do
  case "$arg" in
    --install) INSTALL=1 ;;
    *) echo "Unknown option: $arg (see the top of build.sh)"; exit 1 ;;
  esac
done

step() { printf '\n\033[1m▸ %s\033[0m\n' "$*"; }

VERSION="$(sed -n 's/^version = "\(.*\)"/\1/p' ../pyproject.toml | head -n 1)"
BUILD_NUMBER="${BUILD_NUMBER:-$(date -u +%Y%m%d%H%M)}"
OUT="build"
APP="$OUT/Eco.app"
RES="$APP/Contents/Resources"
TMP="$OUT/tmp"

step "Compiling Eco $VERSION ($BUILD_NUMBER) for Apple silicon and Intel"
swift build -c release --arch arm64 --arch x86_64
BIN="$(swift build -c release --arch arm64 --arch x86_64 --show-bin-path)/Eco"

rm -rf "$APP" "$TMP"
mkdir -p "$APP/Contents/MacOS" "$RES/engine" "$TMP"
cp "$BIN" "$APP/Contents/MacOS/Eco"

cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleIdentifier</key><string>com.danielmadac.Eco</string>
  <key>CFBundleName</key><string>Eco</string>
  <key>CFBundleDisplayName</key><string>Eco</string>
  <key>CFBundleExecutable</key><string>Eco</string>
  <key>CFBundleIconFile</key><string>AppIcon</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>$VERSION</string>
  <key>CFBundleVersion</key><string>$BUILD_NUMBER</string>
  <key>LSMinimumSystemVersion</key><string>13.0</string>
  <key>LSApplicationCategoryType</key><string>public.app-category.video</string>
  <key>NSHighResolutionCapable</key><true/>
  <key>NSHumanReadableCopyright</key><string>MIT licensed. FabLab 503.</string>
</dict>
</plist>
PLIST

step "App icon"
ICONSET="$TMP/AppIcon.iconset"
mkdir -p "$ICONSET"
for size in 16 32 128 256 512; do
  sips -z "$size" "$size" Resources/AppIcon.png --out "$ICONSET/icon_${size}x${size}.png" >/dev/null
  double=$((size * 2))
  sips -z "$double" "$double" Resources/AppIcon.png --out "$ICONSET/icon_${size}x${size}@2x.png" >/dev/null
done
iconutil -c icns "$ICONSET" -o "$RES/AppIcon.icns"

step "Downloading uv for both kinds of Mac"
if [ -n "${UV_VERSION:-}" ]; then
  UV_BASE="https://github.com/astral-sh/uv/releases/download/$UV_VERSION"
else
  UV_BASE="https://github.com/astral-sh/uv/releases/latest/download"
fi
for arch in aarch64 x86_64; do
  name="uv-$arch-apple-darwin"
  curl -fsSL -o "$TMP/$name.tar.gz" "$UV_BASE/$name.tar.gz"
  curl -fsSL -o "$TMP/$name.tar.gz.sha256" "$UV_BASE/$name.tar.gz.sha256"
  (cd "$TMP" && shasum -a 256 -c "$name.tar.gz.sha256")
  tar -xzf "$TMP/$name.tar.gz" -C "$TMP"
done
lipo -create -output "$RES/uv" "$TMP/uv-aarch64-apple-darwin/uv" "$TMP/uv-x86_64-apple-darwin/uv"
chmod +x "$RES/uv"

step "Packaging the Python engine"
UV_PYTHON_INSTALL_DIR="$PWD/$TMP/python" UV_CACHE_DIR="$PWD/$TMP/cache" \
  "$RES/uv" build --wheel --out-dir "$RES/engine" ..
# Extra wheels to ship, e.g. the Intel build of monotonic-alignment-search made by CI.
if [ -n "${EXTRA_WHEELS:-}" ] && ls "$EXTRA_WHEELS"/*.whl >/dev/null 2>&1; then
  cp "$EXTRA_WHEELS"/*.whl "$RES/engine/"
fi
cp install-engine.sh "$RES/install-engine.sh"
cp ../THIRD_PARTY.md "$RES/THIRD_PARTY.md"

step "Signing"
if [ -n "${SIGN_IDENTITY:-}" ]; then
  codesign --force --options runtime --timestamp --sign "$SIGN_IDENTITY" "$RES/uv"
  codesign --force --options runtime --timestamp --sign "$SIGN_IDENTITY" "$APP"
else
  codesign --force --sign - "$RES/uv"
  codesign --force --sign - "$APP"
fi
codesign --verify --strict "$APP"

if [ -n "${SIGN_IDENTITY:-}" ] && [ -n "${NOTARY_KEY:-}" ]; then
  step "Notarising with Apple (a few minutes)"
  ditto -c -k --keepParent "$APP" "$TMP/notarize.zip"
  xcrun notarytool submit "$TMP/notarize.zip" --key "$NOTARY_KEY" --key-id "$NOTARY_KEY_ID" \
    --issuer "$NOTARY_ISSUER" --wait
  xcrun stapler staple "$APP"
  spctl --assess --type execute --verbose "$APP"
fi

rm -rf "$TMP" "$OUT/Eco-mac.zip"
ditto -c -k --keepParent "$APP" "$OUT/Eco-mac.zip"
echo "Built $APP and $OUT/Eco-mac.zip"

if [ "$INSTALL" = 1 ]; then
  step "Installing in /Applications"
  rm -rf "/Applications/Eco.app"
  cp -R "$APP" /Applications/
  echo "Open Eco from Applications."
fi

#!/bin/bash
# Puts the official page's screens (site_screens.py: out/site/<lang>/{mac,ipad,iphone}.png) into
# Apple's product bezels and writes the page's images: img/mac-<lang>.webp (MacBook Pro 14") and
# img/ipad-iphone-<lang>.webp (iPad Pro 13" and iPhone 17 Pro side by side, at the same scale).
#
# Runs on the Mac, with ImageMagick. The bezels are Apple's (developer.apple.com/design/resources,
# "Product Bezels"; Daniel keeps them in his Design Assets folder and accepted Apple's licence on
# 30 Sept 2026). They are not in this repository. Apple's marketing guidelines for them: use them
# as they are (no crop, tilt, shadow or reflection, nothing coming out of the screen), keep several
# products at the same scale, show the app as it appears when running.
#
# usage: frame.sh <screens dir (with en/ and fr/)> <bezels dir (the mounted Bezel-*.dmg images)> <output dir>
set -euo pipefail
export PATH=/opt/homebrew/bin:$PATH
SCREENS=$1 BEZELS=$2 OUT=$3
MAC="$BEZELS/Bezel-MacBook-Pro-M5/PNG/MacBook Pro M5 14-inch Space Black.png"
IPAD="$BEZELS/Bezel-iPad-Pro-(M5)/PNG/iPad Pro (M5) 13\" - Space Black - Portrait.png"
IPHONE="$BEZELS/Bezel-iPhone-17/PNG/iPhone 17 Pro/iPhone 17 Pro - Deep Blue - Portrait.png"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$OUT"

# frame <bezel> <screen> <out>: the screen goes in the bezel's screen area, the transparent part
# around its centre (found with a flood fill; the mask follows the rounded corners, the notch and
# the Dynamic Island are part of the bezel, drawn over the screen)
frame() {
  local bezel=$1 screen=$2 out=$3
  local w h geo
  w=$(magick identify -format '%w' "$bezel"); h=$(magick identify -format '%h' "$bezel")
  magick "$bezel" -alpha extract -threshold 50% -type TrueColor -fill red -draw "color $((w / 2)),$((h / 2)) floodfill" \
    -fill black +opaque red -fill white -opaque red -colorspace gray "$WORK/area.png"
  geo=$(magick "$WORK/area.png" -format '%@' info:)             # WxH+X+Y of the screen
  local sw=${geo%%x*} rest=${geo#*x}
  local sh=${rest%%+*} ox=${rest#*+}
  local oy=${ox#*+}; ox=${ox%%+*}
  local iw ih
  iw=$(magick identify -format '%w' "$screen"); ih=$(magick identify -format '%h' "$screen")
  if [ "$iw" != "$sw" ] || [ "$ih" != "$sh" ]; then
    echo "  $screen is ${iw}x${ih}, the bezel's screen is ${sw}x${sh}" >&2; exit 1
  fi
  # 2 px more under the bezel's inner edge, so its soft edge has the screen behind it, not the page
  magick "$WORK/area.png" -morphology Dilate Disk:2 "$WORK/mask.png"
  magick -size "${w}x${h}" xc:none "$screen" -geometry "+$ox+$oy" -composite \
    \( "$WORK/mask.png" -alpha off \) -compose CopyOpacity -composite \
    "$bezel" -compose Over -composite "$out"
  echo "  $(basename "$out"): screen ${sw}x${sh} at +$ox+$oy in ${w}x${h}"
}

for lang in en fr; do
  frame "$MAC" "$SCREENS/$lang/mac.png" "$WORK/mac-$lang.png"
  frame "$IPAD" "$SCREENS/$lang/ipad.png" "$WORK/ipad-$lang.png"
  frame "$IPHONE" "$SCREENS/$lang/iphone.png" "$WORK/iphone-$lang.png"

  # the Mac on its own
  magick "$WORK/mac-$lang.png" -trim +repage -resize 1600x -quality 86 -define webp:alpha-quality=100 "$OUT/mac-$lang.webp"

  # iPad and iPhone at the same scale: pixels per millimetre of each screen (264 and 460 pixels
  # per inch), so the iPhone is scaled by 264/460; side by side, standing on the same line
  magick "$WORK/iphone-$lang.png" -trim +repage -resize "$(awk 'BEGIN { printf "%.4f", 264 / 460 * 100 }')%" "$WORK/iphone-small.png"
  magick "$WORK/ipad-$lang.png" -trim +repage "$WORK/ipad-trim.png"
  magick "$WORK/ipad-trim.png" \( -size 150x10 xc:none \) "$WORK/iphone-small.png" -background none -gravity south +append \
    -resize 1600x -quality 86 -define webp:alpha-quality=100 "$OUT/ipad-iphone-$lang.webp"
done
for f in "$OUT"/*.webp; do echo "  $(basename "$f")  $(magick identify -format '%wx%h' "$f")  $(( $(stat -f %z "$f") / 1024 )) KB"; done

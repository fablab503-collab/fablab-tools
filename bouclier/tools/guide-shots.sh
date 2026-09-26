#!/bin/bash
# Takes the screenshots for the install guide (site/install.html), one step at a time.
# Run it on a Mac: it tells you which screen to open, you press Return, it saves the picture
# under the name the guide expects in site/img/install/. Pictures that already exist are
# skipped, so you can stop and start again; delete a file to retake it.
#
#   tools/guide-shots.sh iphone   # iPhone Simulator (./build.sh --sim first)
#   tools/guide-shots.sh ipad     # iPad Simulator  (boot an iPad, install Bouclier on it)
#   tools/guide-shots.sh mac      # Safari on this Mac (Bouclier installed with ./build.sh)
#
# With several simulators booted, pick one: SIM=<UDID> tools/guide-shots.sh iphone
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=site/img/install
mkdir -p "$OUT"
SIM=${SIM:-booted}
MODE=${1:-}

shot() { # name, instruction
  local file="$OUT/$1.png"
  if [ -f "$file" ]; then echo "✓ $1 (already there)"; return; fi
  echo
  echo "▶ $2"
  if [ "$MODE" = mac ]; then
    read -r -p "  Press Return, then click the window to capture… " _
    screencapture -i -o -w "$file"
  else
    read -r -p "  Press Return to capture the Simulator screen… " _
    xcrun simctl io "$SIM" screenshot --type=png "$file" >/dev/null
  fi
  [ -f "$file" ] || { echo "  (nothing saved, run again to retake)"; return; }
  sips -Z 1400 "$file" >/dev/null   # keeps the pages light
  echo "  saved $file"
}

sim_ready() {
  xcrun simctl list devices booted | grep -q Booted ||
    { echo "No Simulator is running. Start one (./build.sh --sim does it for iPhone)."; exit 1; }
  # 9:41, full battery and signal, like Apple's own pictures
  xcrun simctl status_bar "$SIM" override --time 9:41 --batteryState charged --batteryLevel 100 \
    --cellularBars 4 --wifiBars 3 >/dev/null 2>&1 || true
}

case "$MODE" in
  iphone)
    sim_ready
    shot iphone-app          "Open the Bouclier app on the Home Screen."
    shot iphone-extensions   "Open Settings → Apps → Safari → Extensions (Bouclier listed)."
    shot iphone-bouclier     "Tap Bouclier: Allow Extension on, All Websites set to Allow, Allow in Private Browsing on."
    shot iphone-page-menu    "In Safari, open a website and tap the page menu button in the address bar (Bouclier listed)."
    shot iphone-popup        "Tap Bouclier: its menu with what was blocked on the page."
    ;;
  ipad)
    sim_ready
    shot ipad-bouclier       "Open Settings → Apps → Safari → Extensions → Bouclier (turned on, All Websites set to Allow)."
    shot ipad-popup          "In Safari, open a website, then the page menu → Bouclier."
    ;;
  mac)
    shot mac-app             "Open the Bouclier app (its window says whether Bouclier is on)."
    shot mac-extensions      "Safari → Settings… → Extensions, Bouclier ticked and selected."
    shot mac-edit-websites   "Click Edit Websites…: Other websites set to Allow."
    shot mac-popup           "Open a website and click Bouclier's shield in the toolbar."
    shot mac-setup-check     "In Bouclier's menu, open the setup check page."
    ;;
  *) sed -n 2,10p "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
echo
echo "Done. The guide shows every picture in $OUT; commit them to publish."

#!/bin/bash
# Weekly filter-list update for Bouclier (macOS LaunchAgent).
#
#   tools/auto-update.sh install    run every Sunday at 10:00 (and at the next login if the Mac was off)
#   tools/auto-update.sh uninstall  stop the weekly update
#   tools/auto-update.sh run        update now: fresh lists, then rebuild the app if Xcode is set up
#   tools/auto-update.sh status     show whether the weekly update is installed and the last log lines
#
# Log: ~/Library/Logs/bouclier-update.log
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
LABEL="com.danielmadac.bouclier-update"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
LOG="$HOME/Library/Logs/bouclier-update.log"
export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"

case "${1:-status}" in
  install)
    mkdir -p "$HOME/Library/LaunchAgents" "$HOME/Library/Logs"
    cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key>
  <array><string>/bin/bash</string><string>$HERE/tools/auto-update.sh</string><string>run</string></array>
  <key>StartCalendarInterval</key>
  <dict><key>Weekday</key><integer>0</integer><key>Hour</key><integer>10</integer><key>Minute</key><integer>0</integer></dict>
  <key>StandardOutPath</key><string>$LOG</string>
  <key>StandardErrorPath</key><string>$LOG</string>
</dict>
</plist>
EOF
    launchctl bootout "gui/$(id -u)/$LABEL" >/dev/null 2>&1 || true
    launchctl bootstrap "gui/$(id -u)" "$PLIST"
    echo "Weekly update installed (Sundays 10:00). Log: $LOG"
    ;;
  uninstall)
    launchctl bootout "gui/$(id -u)/$LABEL" >/dev/null 2>&1 || true
    rm -f "$PLIST"
    echo "Weekly update removed."
    ;;
  run)
    echo "== $(date '+%Y-%m-%d %H:%M') Bouclier update"
    cd "$HERE"
    if [ -d /Applications/Xcode.app ] || xcrun --find xcodebuild >/dev/null 2>&1; then
      ./build.sh || { echo "full build failed, refreshing the rules only"; ./build.sh --lists; }
    else
      ./build.sh --lists
      echo "Xcode not found: rules refreshed in extension/ only (reload the temporary extension in Safari)."
    fi
    ;;
  status)
    if [ -f "$PLIST" ]; then echo "Weekly update: installed ($PLIST)"; else echo "Weekly update: not installed"; fi
    [ -f "$LOG" ] && tail -n 5 "$LOG" || true
    ;;
  *)
    echo "usage: $0 install|uninstall|run|status"; exit 2
    ;;
esac

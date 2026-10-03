#!/bin/bash
# Experiment (3 Oct 2026): a copy of Bouclier's simulator build whose rules are written out one host per
# rule (expand-rules.py), signed again for the simulator. Then, for example:
#   APP="$(./expanded-app.sh)" TAG=" - expanded rules" FRESH=1 ./run.sh <simulator udid>
set -euo pipefail
cd "$(dirname "$0")"
SRC=../../build/DerivedData/Build/Products/Debug-iphonesimulator/Bouclier.app
DIR=build/expanded
rm -rf "$DIR"; mkdir -p "$DIR"
ditto "$SRC" "$DIR/Bouclier.app"
/usr/bin/python3 expand-rules.py "$DIR/Bouclier.app/PlugIns/Bouclier Extension.appex/rules" >&2
codesign --force --sign - --preserve-metadata=identifier,entitlements,flags "$DIR/Bouclier.app/PlugIns/Bouclier Extension.appex" >&2
codesign --force --sign - --preserve-metadata=identifier,entitlements,flags "$DIR/Bouclier.app" >&2
codesign --verify --deep --strict "$DIR/Bouclier.app" >&2
echo "$PWD/$DIR/Bouclier.app"

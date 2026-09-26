#!/bin/bash
# Installs Eco's Python engine into a private folder. The Mac app runs this on first launch
# (and after an update); CI runs it to prove the install works on both kinds of Mac.
#
#   install-engine.sh UV ENGINE_DIR ECO_WHEEL
#
# UV is the uv binary, ENGINE_DIR where Python and the packages go, ECO_WHEEL the eco-dub
# wheel built by build.sh (a wheel, so installing never writes into the signed app bundle).
# Nothing is installed outside ENGINE_DIR.
set -euo pipefail

UV="$1"
ENGINE="$2"
WHEEL="$3"

event() { printf '@eco {"event": "step", "text": "%s"}\n' "$1"; }

mkdir -p "$ENGINE"
export UV_PYTHON_INSTALL_DIR="$ENGINE/python"
export UV_CACHE_DIR="$ENGINE/cache"
export UV_NO_CONFIG=1

event "Installing Python"
if [ ! -x "$ENGINE/venv/bin/python" ]; then
  "$UV" venv --python 3.11 --python-preference only-managed "$ENGINE/venv"
fi

# Apple silicon: Chatterbox on the GPU, Whisper through MLX. Intel: PyTorch stops at 2.2 on
# Intel Macs, so the voice is XTTS (see the intel-mac extra in pyproject.toml).
if [ "$(uname -m)" = "arm64" ]; then
  EXTRAS="apple,argos"
else
  EXTRAS="intel-mac,argos"
fi

event "Installing the dubbing engine (several GB, this is the long part)"
PY="$ENGINE/venv/bin/python"
WHEEL_URL="$("$PY" -c 'import pathlib, sys; print(pathlib.Path(sys.argv[1]).resolve().as_uri())' "$WHEEL")"
"$UV" pip install --python "$PY" --reinstall-package eco-dub "eco-dub[$EXTRAS] @ $WHEEL_URL"

event "Checking the engine"
"$ENGINE/venv/bin/python" - <<'PY'
import importlib, platform
modules = ["eco.cli", "demucs.api", "faster_whisper", "imageio_ffmpeg", "argostranslate.translate"]
modules += ["chatterbox.mtl_tts", "mlx_whisper"] if platform.machine() == "arm64" else ["TTS.api"]
for name in modules:
    importlib.import_module(name)
import torch
print(f"engine ready: torch {torch.__version__}, {platform.machine()}, "
      f"Apple GPU {'available' if torch.backends.mps.is_available() else 'not available'}")
PY
# The download cache is only needed during the install.
"$UV" cache clean >/dev/null 2>&1 || true

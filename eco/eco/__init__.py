"""Eco: dub your videos into other languages in your own voice."""

import os as _os
import platform as _platform
import sys as _sys

__version__ = "0.2.0"

if _sys.platform == "darwin" and _platform.machine() == "x86_64":
    # On Intel Macs PyTorch, CTranslate2 and friends each bring their own copy of Intel's
    # OpenMP runtime, and by default the second copy to load aborts or stalls the process.
    # This is Intel's documented switch for letting them coexist; set before any of them loads.
    _os.environ.setdefault("KMP_DUPLICATE_LIB_OK", "TRUE")

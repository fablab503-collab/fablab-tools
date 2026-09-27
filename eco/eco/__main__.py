import sys

from .cli import main

# Guarded: transcription runs in a spawned process, which re-imports this module.
if __name__ == "__main__":
    sys.exit(main())

"""python -m eco.prefetch: download every model a dub needs, ahead of the first dub.

The Mac app runs this once after installing the engine, so the first dub starts straight away
instead of stalling on several gigabytes of downloads halfway through.
"""

from __future__ import annotations

import argparse
import sys

from . import cli, models


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="python -m eco.prefetch", description=__doc__.splitlines()[0])
    p.add_argument("--engine", choices=["auto", *sorted(models.ENGINES)], default="auto")
    p.add_argument("--whisper-model", default="large-v3")
    p.add_argument("--separator-model", default="htdemucs_ft")
    p.add_argument("--progress-json", action="store_true")
    args = p.parse_args(argv)
    cli.PROGRESS_JSON = args.progress_json
    engine = models.default_engine() if args.engine == "auto" else args.engine

    try:
        cli.step("Downloading the voice separation model")
        from demucs.pretrained import get_model

        get_model(args.separator_model)

        cli.step("Downloading the speech recognition model")
        if models.apple_silicon() and args.whisper_model in models.MLX_MODELS:
            try:
                import mlx_whisper  # noqa: F401  (only to know MLX will be used)
                from huggingface_hub import snapshot_download

                snapshot_download(models.MLX_MODELS[args.whisper_model])
            except ImportError:
                from faster_whisper import download_model

                download_model(args.whisper_model)
        else:
            from faster_whisper import download_model

            download_model(args.whisper_model)

        cli.step(f"Downloading the {engine} voice model")
        models.load_engine(engine, "cpu", 0.5, 0.5, None)
    except Exception as error:  # a failed download should reach the app as a message, not a traceback
        print(f"\neco: {error}", file=sys.stderr)
        cli.emit("error", message=str(error))
        return 1
    cli.emit("done", files=[])
    return 0


if __name__ == "__main__":
    sys.exit(main())

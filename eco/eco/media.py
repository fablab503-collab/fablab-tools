"""Everything that goes through ffmpeg: reading the video, speed changes, the final files."""

from __future__ import annotations

import os
import re
import shutil
import subprocess
from pathlib import Path

# ISO 639-1 (what Whisper and the voice engines use) to ISO 639-2 (what MP4 and MKV
# audio-track tags use, and what YouTube reads when a file carries a language).
ISO3 = {
    "ar": "ara", "cs": "ces", "da": "dan", "de": "deu", "el": "ell", "en": "eng", "es": "spa",
    "fi": "fin", "fr": "fra", "he": "heb", "hi": "hin", "hu": "hun", "it": "ita", "ja": "jpn",
    "ko": "kor", "ms": "msa", "nl": "nld", "no": "nor", "pl": "pol", "pt": "por", "ru": "rus",
    "sv": "swe", "sw": "swa", "tr": "tur", "zh": "zho",
}


class MediaError(RuntimeError):
    pass


def ffmpeg() -> str:
    """The ffmpeg to use: $ECO_FFMPEG, then one on the PATH, then the one bundled with imageio-ffmpeg."""
    if os.environ.get("ECO_FFMPEG"):
        return os.environ["ECO_FFMPEG"]
    found = shutil.which("ffmpeg")
    if found:
        return found
    try:
        import imageio_ffmpeg

        return imageio_ffmpeg.get_ffmpeg_exe()
    except (ImportError, RuntimeError):
        raise MediaError(
            "ffmpeg was not found. Install it (https://ffmpeg.org/download.html; on Windows "
            "`winget install ffmpeg`, on Mac `brew install ffmpeg`) or `pip install imageio-ffmpeg`."
        ) from None


def require_ffmpeg() -> None:
    ffmpeg()


def run(args: list[str]) -> None:
    """Run ffmpeg with these arguments."""
    result = subprocess.run([ffmpeg(), "-y", "-v", "error", *args], capture_output=True, text=True)
    if result.returncode != 0:
        tail = "\n".join(result.stderr.strip().splitlines()[-15:])
        raise MediaError(f"ffmpeg failed:\n{tail}")


def probe(path: Path) -> tuple[float, bool]:
    """(duration in seconds, whether the file has a picture).

    Read from ffmpeg's own description of the file, so no ffprobe is needed: the ffmpeg
    bundled with imageio-ffmpeg (what the Mac app uses) comes without it.
    """
    result = subprocess.run([ffmpeg(), "-hide_banner", "-i", str(path)], capture_output=True, text=True)
    info = result.stderr
    match = re.search(r"Duration: (\d+):(\d+):(\d+(?:\.\d+)?)", info)
    if not match:
        raise MediaError(f"Could not read {path}:\n" + "\n".join(info.strip().splitlines()[-5:]))
    h, m, s = match.groups()
    video = any(
        re.search(r"Stream #.*: Video:", line) and "(attached pic)" not in line for line in info.splitlines()
    )
    return int(h) * 3600 + int(m) * 60 + float(s), video


def extract_audio(source: Path, target: Path, sr: int = 44100) -> None:
    run(["-i", str(source), "-vn", "-ac", "2", "-ar", str(sr),
         "-c:a", "pcm_f32le", str(target)])


def convert(source: Path, target: Path, sr: int, speed: float = 1.0, channels: int = 1) -> None:
    """Resampled, and sped up by `speed` with the pitch kept."""
    filters = []
    remaining = speed
    while remaining > 2.0:  # atempo takes 0.5..2 per stage
        filters.append("atempo=2.0")
        remaining /= 2.0
    if abs(remaining - 1.0) > 1e-3:
        filters.append(f"atempo={remaining:.4f}")
    filters.append(f"aresample={sr}")
    run(["-i", str(source), "-ac", str(channels), "-af", ",".join(filters),
         "-c:a", "pcm_f32le", str(target)])


LIMITER = "alimiter=limit=0.95:level=disabled"


def export_audio(mix_wav: Path, target: Path, language: str) -> None:
    """AAC audio on its own, for YouTube Studio's multi-language audio upload."""
    run(["-i", str(mix_wav), "-af", LIMITER, "-c:a", "aac",
         "-b:a", "192k", "-metadata:s:a:0", f"language={ISO3.get(language, language)}", str(target)])


def export_video(video: Path, mix_wav: Path, target: Path, language: str) -> None:
    """The original picture, untouched, with the dubbed soundtrack."""
    run(["-i", str(video), "-i", str(mix_wav),
         "-map", "0:v:0", "-map", "1:a:0", "-c:v", "copy", "-af", LIMITER, "-c:a", "aac",
         "-b:a", "192k", "-metadata:s:a:0", f"language={ISO3.get(language, language)}",
         "-movflags", "+faststart", str(target)])

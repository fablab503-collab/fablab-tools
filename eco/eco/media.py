"""Everything that goes through ffmpeg: reading the video, speed changes, the final files."""

from __future__ import annotations

import json
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


def require_ffmpeg() -> None:
    for tool in ("ffmpeg", "ffprobe"):
        if shutil.which(tool) is None:
            raise MediaError(
                f"{tool} was not found. Install ffmpeg (https://ffmpeg.org/download.html; "
                "on Windows `winget install ffmpeg`, on Mac `brew install ffmpeg`) and try again."
            )


def run(args: list[str]) -> None:
    result = subprocess.run(args, capture_output=True, text=True)
    if result.returncode != 0:
        tail = "\n".join(result.stderr.strip().splitlines()[-15:])
        raise MediaError(f"{args[0]} failed:\n{tail}")


def probe(path: Path) -> tuple[float, bool]:
    """(duration in seconds, whether the file has a picture)."""
    result = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration:stream=codec_type,disposition",
         "-of", "json", str(path)],
        capture_output=True, text=True,
    )
    if result.returncode != 0:
        raise MediaError(f"Could not read {path}: {result.stderr.strip()}")
    info = json.loads(result.stdout)
    video = any(
        s.get("codec_type") == "video" and not s.get("disposition", {}).get("attached_pic")
        for s in info.get("streams", [])
    )
    return float(info["format"]["duration"]), video


def extract_audio(source: Path, target: Path, sr: int = 44100) -> None:
    run(["ffmpeg", "-y", "-v", "error", "-i", str(source), "-vn", "-ac", "2", "-ar", str(sr),
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
    run(["ffmpeg", "-y", "-v", "error", "-i", str(source), "-ac", str(channels), "-af", ",".join(filters),
         "-c:a", "pcm_f32le", str(target)])


LIMITER = "alimiter=limit=0.95:level=disabled"


def export_audio(mix_wav: Path, target: Path, language: str) -> None:
    """AAC audio on its own, for YouTube Studio's multi-language audio upload."""
    run(["ffmpeg", "-y", "-v", "error", "-i", str(mix_wav), "-af", LIMITER, "-c:a", "aac",
         "-b:a", "192k", "-metadata:s:a:0", f"language={ISO3.get(language, language)}", str(target)])


def export_video(video: Path, mix_wav: Path, target: Path, language: str) -> None:
    """The original picture, untouched, with the dubbed soundtrack."""
    run(["ffmpeg", "-y", "-v", "error", "-i", str(video), "-i", str(mix_wav),
         "-map", "0:v:0", "-map", "1:a:0", "-c:v", "copy", "-af", LIMITER, "-c:a", "aac",
         "-b:a", "192k", "-metadata:s:a:0", f"language={ISO3.get(language, language)}",
         "-movflags", "+faststart", str(target)])

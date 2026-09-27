"""Sample-level work: trimming clips, matching loudness, building and mixing the dubbed track."""

from __future__ import annotations

import numpy as np

FRAME = 0.02  # seconds


def to_mono(audio: np.ndarray) -> np.ndarray:
    return audio.mean(axis=1) if audio.ndim == 2 else audio


def _frame_levels(audio: np.ndarray, sr: int) -> np.ndarray:
    size = max(int(sr * FRAME), 1)
    count = len(audio) // size
    if count == 0:
        return np.zeros(0)
    frames = audio[: count * size].reshape(count, size)
    return np.sqrt(np.mean(frames.astype(np.float64) ** 2, axis=1))


def trim_silence(audio: np.ndarray, sr: int, threshold_db: float = -40.0, pad: float = 0.04) -> np.ndarray:
    """Cut leading and trailing silence, relative to the clip's own loudest frame.

    Voice generators pad their output with a little silence at both ends, and that silence
    would count against the time a line has to be spoken in.
    """
    levels = _frame_levels(audio, sr)
    if levels.size == 0 or levels.max() <= 0:
        return audio
    loud = np.nonzero(levels >= levels.max() * 10 ** (threshold_db / 20))[0]
    size = int(sr * FRAME)
    first = max(loud[0] * size - int(pad * sr), 0)
    last = min((loud[-1] + 1) * size + int(pad * sr), len(audio))
    return audio[first:last]


def speech_rms(audio: np.ndarray, sr: int, floor_db: float = -50.0) -> float:
    """RMS over the frames that hold speech, so pauses inside a line do not lower it."""
    levels = _frame_levels(audio, sr)
    active = levels[levels > 10 ** (floor_db / 20)]
    return float(np.sqrt(np.mean(active**2))) if active.size else 0.0


def match_level(clip: np.ndarray, sr: int, target_rms: float, max_gain_db: float = 12.0) -> np.ndarray:
    """Bring a dubbed line to the loudness the original line had.

    Done per line, so a whispered aside stays quiet and a shout stays loud in every language.
    The gain is limited so a line whose original was mostly music does not get blown up.
    """
    current = speech_rms(clip, sr)
    if current <= 0 or target_rms <= 0:
        return clip
    limit = 10 ** (max_gain_db / 20)
    gain = min(max(target_rms / current, 1 / limit), limit)
    return clip * gain


def fade(clip: np.ndarray, sr: int, seconds: float = 0.01) -> np.ndarray:
    n = min(int(sr * seconds), len(clip) // 2)
    if n <= 0:
        return clip
    clip = clip.copy()
    ramp = np.linspace(0.0, 1.0, n)
    clip[:n] *= ramp
    clip[-n:] *= ramp[::-1]
    return clip


def build_track(clips: list[tuple[float, np.ndarray]], length: int, sr: int) -> np.ndarray:
    """Lay mono clips on a silent track at their start times (seconds)."""
    track = np.zeros(length, dtype=np.float32)
    for start, clip in clips:
        at = int(round(start * sr))
        if at >= length:
            continue
        piece = fade(clip, sr)[: length - at]
        track[at : at + len(piece)] += piece.astype(np.float32)
    return track


def mix(voice: np.ndarray, background: np.ndarray | None) -> np.ndarray:
    """Stereo mix of the dubbed voice over the original music and effects."""
    stereo = np.stack([voice, voice], axis=1)
    if background is not None:
        if background.ndim == 1:
            background = np.stack([background, background], axis=1)
        n = min(len(stereo), len(background))
        stereo = stereo[:n] + background[:n, :2]
    peak = float(np.abs(stereo).max()) if stereo.size else 0.0
    if peak > 0.98:
        # Only reached when a loud line lands on a loud beat; the encoder's limiter handles
        # short peaks, this keeps the float mix from clipping before it gets there.
        stereo = stereo * (0.98 / peak)
    return stereo.astype(np.float32)

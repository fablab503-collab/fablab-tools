"""SubRip subtitles, one per language, for YouTube's captions upload."""

from __future__ import annotations

import textwrap


def timestamp(seconds: float) -> str:
    ms = int(round(max(seconds, 0.0) * 1000))
    h, ms = divmod(ms, 3_600_000)
    m, ms = divmod(ms, 60_000)
    s, ms = divmod(ms, 1000)
    return f"{h:02d}:{m:02d}:{s:02d},{ms:03d}"


def srt(entries: list[tuple[float, float, str]], width: int = 42) -> str:
    """entries are (start, end, text); long text is wrapped to two-ish readable lines."""
    blocks = []
    for n, (start, end, text) in enumerate(entries, 1):
        wrapped = "\n".join(textwrap.wrap(text.strip(), width)) or text.strip()
        blocks.append(f"{n}\n{timestamp(start)} --> {timestamp(end)}\n{wrapped}\n")
    return "\n".join(blocks)

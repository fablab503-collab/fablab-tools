"""Turning timed words into dubbing lines, and reference windows for the voice.

A dubbing line should be one spoken sentence or phrase: long enough that the translation reads
naturally, short enough that the dubbed voice stays in sync with the picture. Whisper's own
segments are cut for subtitles and often split mid-sentence, so lines are rebuilt from its
word timestamps instead.
"""

from __future__ import annotations

from dataclasses import dataclass

SENTENCE_END = (".", "?", "!", "…", "。", "？", "！")
SOFT_BREAK = (",", ";", ":", "，", "、", "；", "：")


@dataclass
class Word:
    start: float
    end: float
    text: str


@dataclass
class Line:
    id: int
    start: float
    end: float
    text: str

    @property
    def duration(self) -> float:
        return self.end - self.start


def _join(words: list[Word]) -> str:
    # faster-whisper words carry their own leading space (" Hello"), and none for scripts
    # written without spaces, so a plain concatenation is right for both.
    return "".join(w.text for w in words).strip()


def group_words(
    words: list[Word],
    max_seconds: float = 12.0,
    pause: float = 0.7,
    min_seconds: float = 0.6,
) -> list[Line]:
    """Group words into lines at sentence ends and pauses, never much longer than max_seconds."""
    groups: list[list[Word]] = []
    current: list[Word] = []
    for word in words:
        if current:
            gap = word.start - current[-1].end
            length = word.end - current[0].start
            previous = current[-1].text.strip()
            if (
                gap >= pause
                or previous.endswith(SENTENCE_END)
                or (length > max_seconds and previous.endswith(SOFT_BREAK))
                or length > max_seconds * 1.5
            ):
                groups.append(current)
                current = []
        current.append(word)
    if current:
        groups.append(current)

    # A lone "OK." right after another line reads better as part of it.
    merged: list[list[Word]] = []
    for group in groups:
        if merged:
            previous = merged[-1]
            short = group[-1].end - group[0].start < min_seconds
            close = group[0].start - previous[-1].end < 0.3
            fits = group[-1].end - previous[0].start <= max_seconds
            if short and close and fits:
                previous.extend(group)
                continue
        merged.append(group)

    lines = []
    for group in merged:
        text = _join(group)
        if text:
            lines.append(Line(id=len(lines) + 1, start=group[0].start, end=group[-1].end, text=text))
    return lines


def reference_indices(lines: list[Line], index: int, min_seconds: float = 4.0, max_seconds: float = 10.0) -> list[int]:
    """Which lines to take the voice sample from when dubbing line `index`.

    The line itself carries the tone the dub should have, but a voice cloner needs a few
    seconds of speech, so a short line borrows its neighbours: the next line first, then the
    previous, alternating, until there is enough speech or adding more would pass max_seconds.
    """
    chosen = [index]
    total = lines[index].duration
    after, before = index + 1, index - 1
    take_after = True
    while total < min_seconds and (after < len(lines) or before >= 0):
        candidate = None
        if take_after and after < len(lines):
            candidate, after = after, after + 1
        elif not take_after and before >= 0:
            candidate, before = before, before - 1
        elif after < len(lines):
            candidate, after = after, after + 1
        else:
            candidate, before = before, before - 1
        take_after = not take_after
        if total + lines[candidate].duration > max_seconds:
            break
        chosen.append(candidate)
        total += lines[candidate].duration
    return sorted(chosen)


def voice_sample_indices(lines: list[Line], target_seconds: float = 10.0) -> list[int]:
    """The longest, cleanest-sounding lines that together give about target_seconds of speech.

    Used for the steady style, where every line is spoken from the same sample. Very short
    lines are mostly breath and edges, very long ones often hold a pause, so mid-length lines
    are preferred.
    """
    candidates = [i for i, line in enumerate(lines) if 2.0 <= line.duration <= 12.0]
    if not candidates:
        candidates = list(range(len(lines)))
    candidates.sort(key=lambda i: lines[i].duration, reverse=True)
    chosen, total = [], 0.0
    for i in candidates:
        if total >= target_seconds:
            break
        chosen.append(i)
        total += lines[i].duration
    return sorted(chosen)

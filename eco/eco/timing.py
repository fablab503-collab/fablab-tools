"""Fitting dubbed lines into the original timeline.

Each dubbed line starts where the original line started. A translation is rarely the same
length as the original, so a line that runs long is first allowed to use the pause after it,
then sped up (up to max_speed, pitch unchanged), and only then allowed to push the next line
back. The push is absorbed by the next pause long enough to take it, so the dub drifts for a
line or two at most instead of falling further and further behind the picture.
"""

from __future__ import annotations

from dataclasses import dataclass

GAP = 0.1  # seconds of air kept between two dubbed lines


@dataclass
class Placement:
    start: float
    speed: float
    duration: float  # after the speed change

    @property
    def end(self) -> float:
        return self.start + self.duration


def windows(starts: list[float], total: float) -> list[float]:
    """Seconds each line may use when it starts on time: up to the next line, less the gap."""
    out = []
    for i, start in enumerate(starts):
        limit = starts[i + 1] - GAP if i + 1 < len(starts) else total
        out.append(max(limit - start, 0.05))
    return out


def too_long(starts: list[float], durations: list[float], total: float, max_speed: float) -> list[int]:
    """Indices of lines that would not fit their window even at max_speed."""
    return [
        i
        for i, (window, duration) in enumerate(zip(windows(starts, total), durations))
        if duration / window > max_speed
    ]


def plan(starts: list[float], durations: list[float], total: float, max_speed: float = 1.25) -> list[Placement]:
    """Where each dubbed line goes and how much it is sped up."""
    placements: list[Placement] = []
    cursor = 0.0
    for i, (start, duration) in enumerate(zip(starts, durations)):
        begin = max(start, cursor + GAP) if placements else start
        limit = starts[i + 1] - GAP if i + 1 < len(starts) else total
        room = max(limit - begin, 0.05)
        speed = min(max(duration / room, 1.0), max_speed)
        placement = Placement(start=begin, speed=speed, duration=duration / speed)
        placements.append(placement)
        cursor = placement.end
    return placements

import numpy as np
import pytest

from eco import audio, timing
from eco.models import plausible
from eco.segments import Line, Word, group_words, reference_indices, voice_sample_indices
from eco.subtitles import srt, timestamp


def words(spec):
    """spec: list of (start, end, text)"""
    return [Word(s, e, t) for s, e, t in spec]


# --- lines -------------------------------------------------------------------------------------

def test_lines_break_at_sentence_ends():
    lines = group_words(words([
        (0.0, 0.4, " Hello"), (0.4, 0.9, " everyone."), (1.0, 1.3, " Today"), (1.3, 1.6, " we"),
        (1.6, 2.2, " ride."),
    ]))
    assert [l.text for l in lines] == ["Hello everyone.", "Today we ride."]
    assert (lines[1].start, lines[1].end) == (1.0, 2.2)
    assert [l.id for l in lines] == [1, 2]


def test_lines_break_at_long_pauses_without_punctuation():
    lines = group_words(words([(0.0, 0.5, " so"), (0.5, 1.0, " yeah"), (2.0, 2.5, " anyway"), (2.5, 3.0, " next")]))
    assert [l.text for l in lines] == ["so yeah", "anyway next"]


def test_long_run_on_sentence_is_cut_at_a_comma():
    spec, t = [], 0.0
    for i in range(40):
        spec.append((t, t + 0.4, f" w{i}" + ("," if i == 30 else "")))
        t += 0.45
    lines = group_words(words(spec), max_seconds=12.0)
    assert len(lines) == 2
    assert lines[0].text.endswith("w30,")


def test_tiny_line_joins_the_one_before():
    lines = group_words(words([(0.0, 0.5, " Right."), (0.6, 0.9, " OK."), (2.0, 3.0, " Next thing.")]))
    assert [l.text for l in lines] == ["Right. OK.", "Next thing."]


def test_reference_takes_neighbours_until_long_enough():
    lines = [Line(i + 1, i * 3.0, i * 3.0 + d, "x") for i, d in enumerate([2.0, 1.0, 2.0, 2.5, 2.0])]
    assert reference_indices(lines, 1) == [0, 1, 2]  # next, then previous
    assert reference_indices(lines, 4) == [3, 4]
    long = [Line(1, 0, 8, "x"), Line(2, 9, 10, "y")]
    assert reference_indices(long, 0) == [0]


def test_voice_sample_prefers_mid_length_lines():
    lines = [Line(i + 1, i * 20.0, i * 20.0 + d, "x") for i, d in enumerate([1.0, 6.0, 15.0, 5.0, 3.0])]
    assert voice_sample_indices(lines) == [1, 3]


# --- timing ------------------------------------------------------------------------------------

def test_line_that_fits_is_left_alone():
    p = timing.plan([1.0, 5.0], [2.0, 2.0], total=10.0)
    assert [(x.start, x.speed) for x in p] == [(1.0, 1.0), (5.0, 1.0)]


def test_long_line_is_sped_up_within_the_limit():
    p = timing.plan([0.0, 3.0], [3.3, 1.0], total=10.0, max_speed=1.25)
    assert p[0].speed == pytest.approx(3.3 / 2.9)
    assert p[1].start == 3.0


def test_overflow_pushes_next_line_then_recovers():
    p = timing.plan([0.0, 2.0, 10.0], [4.0, 1.0, 1.0], total=12.0, max_speed=1.25)
    assert p[0].speed == 1.25
    assert p[1].start == pytest.approx(3.2 + timing.GAP)
    assert p[2].start == 10.0


def test_too_long_lists_lines_beyond_max_speed():
    assert timing.too_long([0.0, 2.0, 10.0], [4.0, 1.0, 1.0], 12.0, 1.25) == [0]


def test_last_line_may_run_to_the_end():
    p = timing.plan([0.0], [5.0], total=4.0)
    assert p[0].speed == pytest.approx(1.25)


# --- audio -------------------------------------------------------------------------------------

SR = 48000


def tone(seconds, amp=0.5):
    t = np.arange(int(seconds * SR)) / SR
    return (amp * np.sin(2 * np.pi * 220 * t)).astype(np.float32)


def test_trim_silence_cuts_both_ends():
    clip = np.concatenate([np.zeros(SR), tone(1.0), np.zeros(SR // 2)])
    trimmed = audio.trim_silence(clip, SR)
    assert 1.0 <= len(trimmed) / SR <= 1.1


def test_level_matching_follows_the_original_and_is_capped():
    quiet = audio.match_level(tone(1, 0.3), SR, audio.speech_rms(tone(1, 0.1), SR))
    assert audio.speech_rms(quiet, SR) == pytest.approx(audio.speech_rms(tone(1, 0.1), SR), rel=1e-3)
    capped = audio.match_level(tone(1, 0.01), SR, 0.5)
    assert np.abs(capped).max() == pytest.approx(0.01 * 10 ** (12 / 20), rel=1e-3)


def test_track_and_mix():
    track = audio.build_track([(0.5, tone(0.2)), (1.9, tone(0.5))], 2 * SR, SR)
    assert len(track) == 2 * SR
    assert np.abs(track[: int(0.5 * SR)]).max() == 0
    mixed = audio.mix(track, np.full((2 * SR, 2), 0.9, dtype=np.float32))
    assert mixed.shape == (2 * SR, 2)
    assert np.abs(mixed).max() <= 0.98 + 1e-6


# --- small things ------------------------------------------------------------------------------

def test_srt():
    assert timestamp(3723.456) == "01:02:03,456"
    text = srt([(0.0, 1.5, "Hola a todos"), (2.0, 3.0, "Vamos")])
    assert text.startswith("1\n00:00:00,000 --> 00:00:01,500\nHola a todos\n")
    assert "2\n00:00:02,000 --> 00:00:03,000\nVamos" in text


def test_plausible_catches_babble_and_cutoffs():
    assert plausible(2.0, "Hola a todos, bienvenidos")
    assert not plausible(30.0, "Hola a todos, bienvenidos")
    assert not plausible(3.0, "x" * 200)
    assert plausible(1.0, "Sí")

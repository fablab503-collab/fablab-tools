"""The whole command, end to end, with real ffmpeg and stand-ins for the models."""

import json
import shutil
import subprocess

import numpy as np
import pytest
import soundfile as sf

from eco import cli, models
from eco.segments import Word
from eco.translate import Translator

pytestmark = pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="needs ffmpeg")


class FakeEngine(models.Engine):
    name = "chatterbox"
    calls = []

    def synthesize(self, text, language, reference, target):
        assert reference.exists()
        self.calls.append(text)
        sr = 24000
        seconds = len(text) / 14
        t = np.arange(int(seconds * sr)) / sr
        voice = 0.3 * np.sin(2 * np.pi * 180 * t)
        sf.write(str(target), np.concatenate([np.zeros(sr // 5), voice, np.zeros(sr // 5)]), sr)


class FakeTranslator(Translator):
    can_shorten = True
    shortened = []

    def translate(self, lines, source, target):
        # The second line comes out far too long to fit, like a wordy literal translation.
        return {l["id"]: (f"[{target}] " + l["text"]) * (4 if l["id"] == 2 else 1) for l in lines}

    def shorten(self, lines, source, target):
        self.shortened += [l["id"] for l in lines]
        return {l["id"]: f"[{target}] short" for l in lines}


WORDS = [
    Word(0.5, 1.0, " Hello"), Word(1.0, 1.8, " everyone."),
    Word(3.0, 3.5, " Today"), Word(3.5, 4.0, " we"), Word(4.0, 4.6, " ride."),
    Word(6.0, 6.6, " Let's"), Word(6.6, 7.4, " go."),
]


@pytest.fixture
def video(tmp_path):
    path = tmp_path / "clip.mp4"
    subprocess.run(
        ["ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc=size=160x120:rate=10:duration=9",
         "-f", "lavfi", "-i", "sine=frequency=300:duration=9", "-shortest", "-c:v", "libx264",
         "-pix_fmt", "yuv420p", "-c:a", "aac", str(path)],
        check=True,
    )
    return path


@pytest.fixture
def fakes(monkeypatch):
    FakeEngine.calls = []
    FakeTranslator.shortened = []

    def fake_separate(audio_path, vocals_path, background_path, device, model):
        data, sr = sf.read(str(audio_path), dtype="float32")
        sf.write(str(vocals_path), data, sr)
        sf.write(str(background_path), data * 0.1, sr)

    monkeypatch.setattr(models, "separate", fake_separate)
    monkeypatch.setattr(models, "transcribe", lambda *a: ("en", WORDS))
    monkeypatch.setattr(models, "load_engine", lambda *a: FakeEngine())
    monkeypatch.setattr(cli, "Claude", lambda *a: FakeTranslator())


def test_dubs_a_video(video, fakes, tmp_path):
    out = tmp_path / "out"
    assert cli.main([str(video), "--to", "es,fr", "--out", str(out)]) == 0

    for lang in ("es", "fr"):
        assert (out / f"clip.{lang}.mp4").exists()
        assert (out / f"clip.{lang}.m4a").exists()
        assert f"[{lang}] Hello everyone." in (out / f"clip.{lang}.srt").read_text()
        probe = subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries", "stream=codec_type:stream_tags=language",
             "-of", "json", str(out / f"clip.{lang}.mp4")],
            capture_output=True, text=True, check=True,
        )
        streams = json.loads(probe.stdout)["streams"]
        assert [s["codec_type"] for s in streams] == ["video", "audio"]
        assert streams[1]["tags"]["language"] == {"es": "spa", "fr": "fra"}[lang]
    assert (out / "clip.en.srt").exists()

    work = tmp_path / "clip.eco"
    es = json.loads((work / "es.json").read_text())["lines"]
    assert [l["source"] for l in es] == ["Hello everyone.", "Today we ride.", "Let's go."]
    assert es[1]["text"] == "[es] short"  # the overlong line was rephrased
    assert set(FakeTranslator.shortened) == {2}


def test_second_run_reuses_everything_and_revoices_only_edits(video, fakes, tmp_path):
    out = tmp_path / "out"
    cli.main([str(video), "--to", "es", "--out", str(out)])
    first_run = len(FakeEngine.calls)

    path = tmp_path / "clip.eco" / "es.json"
    data = json.loads(path.read_text())
    data["lines"][0]["text"] = "Hola a todos."
    path.write_text(json.dumps(data))

    FakeEngine.calls = []
    assert cli.main([str(video), "--to", "es", "--out", str(out)]) == 0
    assert FakeEngine.calls == ["Hola a todos."]
    assert first_run >= 3


def test_review_stops_before_voicing(video, fakes, tmp_path):
    assert cli.main([str(video), "--to", "es", "--review"]) == 0
    assert (tmp_path / "clip.eco" / "es.json").exists()
    assert FakeEngine.calls == []
    assert not (tmp_path / "clip.es.mp4").exists()


def test_rejects_a_language_the_voice_cannot_speak(video, fakes, capsys):
    assert cli.main([str(video), "--to", "xx"]) == 1
    assert "cannot speak xx" in capsys.readouterr().err

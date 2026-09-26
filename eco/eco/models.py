"""The machine-learning pieces: separating voice from music, transcribing, and the voice engines.

Every heavy library is imported inside the function that needs it, so the rest of the tool
(and its tests) runs without a GPU stack installed.
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
import soundfile as sf

from .segments import Word


def resolve_device(preference: str) -> str:
    if preference != "auto":
        return preference
    try:
        import torch
    except ImportError:
        return "cpu"
    if torch.cuda.is_available():
        return "cuda"
    if getattr(torch.backends, "mps", None) and torch.backends.mps.is_available():
        return "mps"
    return "cpu"


# ---------------------------------------------------------------------------------------------
# Separation


def separate(audio_path: Path, vocals_path: Path, background_path: Path, device: str, model: str) -> None:
    """Split the soundtrack into the voice and everything else (music, effects, ambience).

    The voice is what gets transcribed and cloned; everything else goes back under the
    dubbed voice so the dubbed video keeps its music.
    """
    import torch
    from demucs.api import Separator

    audio, sr = sf.read(str(audio_path), dtype="float32", always_2d=True)
    separator = Separator(model=model, device=device if device != "mps" else "cpu", progress=True)
    _, stems = separator.separate_tensor(torch.from_numpy(audio.T.copy()), sr)
    vocals = stems["vocals"]
    background = sum(stem for name, stem in stems.items() if name != "vocals")
    sf.write(str(vocals_path), vocals.cpu().numpy().T, separator.samplerate, subtype="FLOAT")
    sf.write(str(background_path), background.cpu().numpy().T, separator.samplerate, subtype="FLOAT")


# ---------------------------------------------------------------------------------------------
# Transcription


def transcribe(audio_path: Path, model_name: str, device: str, language: str | None) -> tuple[str, list[Word]]:
    """Words with timestamps, and the spoken language (detected unless given)."""
    from faster_whisper import WhisperModel

    # faster-whisper runs on CUDA or the CPU; Apple GPUs are not supported by its backend.
    run_on = "cuda" if device == "cuda" else "cpu"
    model = WhisperModel(model_name, device=run_on, compute_type="float16" if run_on == "cuda" else "int8")
    segments, info = model.transcribe(
        str(audio_path),
        language=language,
        word_timestamps=True,
        vad_filter=True,
        beam_size=5,
        # Carrying text over between windows is what makes Whisper repeat itself on music
        # and long pauses, which YouTube videos are full of.
        condition_on_previous_text=False,
    )
    words: list[Word] = []
    for segment in segments:
        print(f"  [{segment.start:7.1f}s] {segment.text.strip()}")
        for w in segment.words or []:
            words.append(Word(start=float(w.start), end=float(w.end), text=w.word))
    return info.language, words


# ---------------------------------------------------------------------------------------------
# Voice engines


class Engine:
    name = ""
    languages: set[str] = set()
    license_note = ""

    def synthesize(self, text: str, language: str, reference: Path, target: Path) -> None:
        raise NotImplementedError


class Chatterbox(Engine):
    """Resemble AI's Chatterbox Multilingual: 23 languages, MIT licence, fine for monetised videos."""

    name = "chatterbox"
    languages = {
        "ar", "da", "de", "el", "en", "es", "fi", "fr", "he", "hi", "it", "ja", "ko", "ms", "nl",
        "no", "pl", "pt", "ru", "sv", "sw", "tr", "zh",
    }
    license_note = "Chatterbox (MIT). Output carries Resemble's inaudible PerTh watermark."

    def __init__(self, device: str, exaggeration: float, cfg_weight: float, temperature: float | None):
        from chatterbox.mtl_tts import ChatterboxMultilingualTTS

        self.model = ChatterboxMultilingualTTS.from_pretrained(device=device)
        self.exaggeration = exaggeration
        self.cfg_weight = cfg_weight
        self.temperature = 0.8 if temperature is None else temperature

    def synthesize(self, text, language, reference, target):
        wav = self.model.generate(
            text,
            language_id=language,
            audio_prompt_path=str(reference),
            exaggeration=self.exaggeration,
            cfg_weight=self.cfg_weight,
            temperature=self.temperature,
        )
        sf.write(str(target), wav.squeeze(0).cpu().numpy(), self.model.sr)


class Xtts(Engine):
    """Coqui XTTS-v2: 17 languages, very natural, but its licence forbids commercial use."""

    name = "xtts"
    languages = {"ar", "cs", "de", "en", "es", "fr", "hi", "hu", "it", "ja", "ko", "nl", "pl", "pt", "ru", "tr", "zh"}
    license_note = "XTTS-v2 (Coqui Public Model License: non-commercial use only)."

    def __init__(self, device: str, temperature: float | None):
        from TTS.api import TTS

        self.model = TTS("tts_models/multilingual/multi-dataset/xtts_v2").to(device if device != "mps" else "cpu")
        self.temperature = temperature

    def synthesize(self, text, language, reference, target):
        settings = {} if self.temperature is None else {"temperature": self.temperature}
        self.model.tts_to_file(
            text=text,
            speaker_wav=str(reference),
            language="zh-cn" if language == "zh" else language,
            file_path=str(target),
            **settings,
        )


ENGINES = {"chatterbox": Chatterbox, "xtts": Xtts}


def load_engine(name: str, device: str, exaggeration: float, cfg_weight: float, temperature: float | None) -> Engine:
    if name == "chatterbox":
        return Chatterbox(device, exaggeration, cfg_weight, temperature)
    if name == "xtts":
        return Xtts(device, temperature)
    raise ValueError(f"Unknown voice engine {name!r}")


def plausible(seconds: float, text: str) -> bool:
    """Whether a generated clip is a sane length for its text.

    Voice cloners sometimes babble on past the end of the sentence, or stop after a word.
    Normal speech runs about 10-20 characters a second in most scripts; this only catches
    clips far outside that.
    """
    chars = len(text.strip())
    if chars == 0:
        return True
    rate = chars / max(seconds, 1e-3)
    return 2.5 <= rate <= 45 or seconds < 1.5

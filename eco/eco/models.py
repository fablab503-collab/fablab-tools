"""The machine-learning pieces: separating voice from music, transcribing, and the voice engines.

Every heavy library is imported inside the function that needs it, so the rest of the tool
(and its tests) runs without a GPU stack installed.
"""

from __future__ import annotations

import multiprocessing
import os
import platform
import sys
from concurrent.futures import ProcessPoolExecutor
from concurrent.futures.process import BrokenProcessPool
from pathlib import Path

import numpy as np
import soundfile as sf

from .segments import Word


def resolve_device(preference: str) -> str:
    # Operations Apple's GPU backend lacks run on the CPU instead of stopping the dub.
    os.environ.setdefault("PYTORCH_ENABLE_MPS_FALLBACK", "1")
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
    dubbed voice so the dubbed video keeps its music. Uses demucs' apply_model, which is the
    same in 4.0 (the last release installable on Intel Macs) and 4.1.
    """
    import torch
    from demucs.apply import apply_model
    from demucs.pretrained import get_model

    separator = get_model(model)
    separator.eval()
    audio, sr = sf.read(str(audio_path), dtype="float32", always_2d=True)
    if sr != separator.samplerate:
        raise ValueError(f"expected {separator.samplerate} Hz audio, got {sr}")
    wav = torch.from_numpy(audio.T.copy())
    reference = wav.mean(0)
    mean, std = reference.mean(), reference.std() + 1e-8
    with torch.no_grad():
        stems = apply_model(
            separator, ((wav - mean) / std)[None], device=device if device != "mps" else "cpu",
            shifts=1, split=True, overlap=0.25, progress=True,
        )[0]
    stems = stems * std + mean
    vocals = stems[separator.sources.index("vocals")]
    background = stems.sum(0) - vocals
    sf.write(str(vocals_path), vocals.cpu().numpy().T, separator.samplerate, subtype="FLOAT")
    sf.write(str(background_path), background.cpu().numpy().T, separator.samplerate, subtype="FLOAT")


# ---------------------------------------------------------------------------------------------
# Transcription


MLX_MODELS = {
    "large-v3": "mlx-community/whisper-large-v3-mlx",
    "large-v3-turbo": "mlx-community/whisper-large-v3-turbo",
    "turbo": "mlx-community/whisper-large-v3-turbo",
    "medium": "mlx-community/whisper-medium-mlx",
    "small": "mlx-community/whisper-small-mlx",
    "base": "mlx-community/whisper-base-mlx",
    "tiny": "mlx-community/whisper-tiny-mlx",
}


def apple_silicon() -> bool:
    return sys.platform == "darwin" and platform.machine() == "arm64"


def transcribe(audio_path: Path, model_name: str, device: str, language: str | None) -> tuple[str, list[Word]]:
    """Words with timestamps, and the spoken language (detected unless given)."""
    if apple_silicon() and model_name in MLX_MODELS:
        try:
            return _transcribe_mlx(audio_path, MLX_MODELS[model_name], language)
        except ImportError:
            pass  # mlx-whisper not installed: fall back to faster-whisper on the CPU
        except Exception as error:  # e.g. no Metal GPU, as in virtual machines
            print(f"  MLX Whisper failed ({error}); using the CPU instead", flush=True)
    return _transcribe_faster_whisper(audio_path, model_name, device, language)


def _transcribe_faster_whisper(audio_path, model_name, device, language):
    # In a process of its own: CTranslate2 (under faster-whisper) and PyTorch each ship their
    # own OpenMP runtime, and on Intel Macs loading both into one process aborts the dub.
    # A ProcessPoolExecutor rather than a Pool: if the worker dies, this raises instead of
    # waiting for it forever.
    with ProcessPoolExecutor(max_workers=1, mp_context=multiprocessing.get_context("spawn")) as pool:
        try:
            detected, spans = pool.submit(
                _faster_whisper_worker, str(audio_path), model_name, device, language).result()
        except BrokenProcessPool:
            raise RuntimeError("speech recognition stopped unexpectedly (see the lines above)") from None
    return detected, [Word(start, end, text) for start, end, text in spans]


def _faster_whisper_worker(audio_path, model_name, device, language):
    from faster_whisper import WhisperModel

    # faster-whisper runs on CUDA or the CPU; Apple GPUs are not supported by its backend.
    run_on = "cuda" if device == "cuda" else "cpu"
    model = WhisperModel(model_name, device=run_on, compute_type="float16" if run_on == "cuda" else "int8")
    segments, info = model.transcribe(
        audio_path,
        language=language,
        word_timestamps=True,
        vad_filter=True,
        beam_size=5,
        # Carrying text over between windows is what makes Whisper repeat itself on music
        # and long pauses, which YouTube videos are full of.
        condition_on_previous_text=False,
    )
    spans = []
    for segment in segments:
        print(f"  [{segment.start:7.1f}s] {segment.text.strip()}", flush=True)
        for w in segment.words or []:
            spans.append((float(w.start), float(w.end), w.word))
    return info.language, spans


def _transcribe_mlx(audio_path, repo, language):
    """Whisper on the Apple Silicon GPU through MLX: several times faster than the CPU."""
    import mlx_whisper

    from . import media

    # mlx-whisper would shell out to an `ffmpeg` on the PATH to read the file; hand it the
    # samples instead, 16 kHz mono as Whisper expects.
    pcm = audio_path.with_name(audio_path.stem + ".16k.wav")
    if not pcm.exists():
        media.convert(audio_path, pcm, 16000)
    samples, _ = sf.read(str(pcm), dtype="float32")
    options = {"language": language} if language else {}
    result = mlx_whisper.transcribe(
        samples,
        path_or_hf_repo=repo,
        word_timestamps=True,
        condition_on_previous_text=False,
        **options,
    )
    words: list[Word] = []
    for segment in result["segments"]:
        print(f"  [{segment['start']:7.1f}s] {segment['text'].strip()}", flush=True)
        for w in segment.get("words", []):
            words.append(Word(start=float(w["start"]), end=float(w["end"]), text=w["word"]))
    return result["language"], words


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


def default_engine() -> str:
    """Chatterbox where it installs; XTTS on Intel Macs, where PyTorch stops at 2.2 and
    Chatterbox needs 2.6."""
    import importlib.util

    if importlib.util.find_spec("chatterbox") is None and importlib.util.find_spec("TTS") is not None:
        return "xtts"
    return "chatterbox"


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

"""eco: dub a video into other languages in your own voice."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from dataclasses import asdict
from pathlib import Path

import numpy as np
import soundfile as sf

from . import audio, media, models, timing
from .media import MediaError
from .segments import Line, group_words, reference_indices, voice_sample_indices
from .subtitles import srt
from .translate import Argos, Claude, TranslationError, Translator, language_name

MIX_SR = 48000


class EcoError(RuntimeError):
    pass


def parse(argv: list[str] | None) -> argparse.Namespace:
    p = argparse.ArgumentParser(
        prog="eco",
        description="Dub a video into other languages in your own voice.",
        epilog="Example: eco my-video.mp4 --to es,fr,pt",
    )
    p.add_argument("input", help="the video (or audio) to dub")
    p.add_argument("--to", required=True, help="target languages, comma separated ISO codes: es,fr,pt,de...")
    p.add_argument("--from", dest="source", help="spoken language of the video (detected if left out)")
    p.add_argument("--out", help="folder for the finished files (default: next to the video)")
    p.add_argument("--review", action="store_true",
                   help="stop after translating, so you can read and fix the script before voicing it")
    p.add_argument("--notes", default="",
                   help='what the translator should know, e.g. "cycling vlog, casual, keep bike part names in English"')

    q = p.add_argument_group("quality")
    q.add_argument("--engine", choices=sorted(models.ENGINES), default="chatterbox",
                   help="voice engine: chatterbox (23 languages, MIT, default) or xtts (17 languages, non-commercial)")
    q.add_argument("--style", choices=["line", "steady"], default="line",
                   help="line: each line is voiced from the same moment of the original, so its tone and "
                        "energy carry over (default). steady: one sample for the whole video, most consistent voice")
    q.add_argument("--voice-sample", help="a clean recording of your voice to clone from instead of the video")
    q.add_argument("--translator", choices=["claude", "argos"], default="claude",
                   help="claude (best, needs an Anthropic API key) or argos (free, offline, literal)")
    q.add_argument("--claude-model", default="claude-opus-5", help="Claude model for translation")
    q.add_argument("--max-speed", type=float, default=1.25,
                   help="how much a long line may be sped up to stay in sync (default 1.25)")
    q.add_argument("--no-shorten", action="store_true",
                   help="do not ask Claude to rephrase lines that are too long to fit")
    q.add_argument("--exaggeration", type=float, default=0.5,
                   help="chatterbox: emotional intensity, 0.25 calm to 1.0 dramatic (default 0.5)")
    q.add_argument("--cfg-weight", type=float, default=0.5,
                   help="chatterbox: lower (0.2-0.3) slows fast talkers down and reduces the original accent")
    q.add_argument("--temperature", type=float, help="voice randomness (engine default if left out)")

    t = p.add_argument_group("advanced")
    t.add_argument("--device", default="auto", help="auto, cuda, mps or cpu")
    t.add_argument("--whisper-model", default="large-v3", help="speech recognition model (default large-v3)")
    t.add_argument("--separator-model", default="htdemucs_ft",
                   help="voice/music separation model: htdemucs_ft (best) or htdemucs (4x faster)")
    t.add_argument("--no-separate", action="store_true",
                   help="skip separating voice from music (for videos with no music at all)")
    t.add_argument("--max-line", type=float, default=12.0, help="longest line in seconds (default 12)")
    return p.parse_args(argv)


def step(text: str) -> None:
    print(f"\n== {text}", flush=True)


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}


def save_json(path: Path, data: dict) -> None:
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def digest(*parts) -> str:
    return hashlib.sha1(json.dumps(parts, ensure_ascii=False).encode()).hexdigest()[:10]


def main(argv: list[str] | None = None) -> int:
    args = parse(argv)
    try:
        return run(args)
    except (EcoError, MediaError, TranslationError) as error:
        print(f"\neco: {error}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\neco: stopped. Run the same command again to carry on where it left off.", file=sys.stderr)
        return 130


def run(args: argparse.Namespace) -> int:
    source = Path(args.input).expanduser().resolve()
    if not source.exists():
        raise EcoError(f"{source} does not exist.")
    media.require_ffmpeg()
    work = source.with_name(source.stem + ".eco")
    work.mkdir(exist_ok=True)
    out_dir = Path(args.out).expanduser().resolve() if args.out else source.parent
    out_dir.mkdir(parents=True, exist_ok=True)
    targets = list(dict.fromkeys(code.strip().lower() for code in args.to.split(",") if code.strip()))
    engine_class = models.ENGINES[args.engine]
    unsupported = [code for code in targets if code not in engine_class.languages]
    if unsupported:
        raise EcoError(
            f"The {args.engine} voice cannot speak {', '.join(unsupported)}. "
            f"It speaks: {', '.join(sorted(engine_class.languages))}."
        )
    total, has_video = media.probe(source)
    device = models.resolve_device(args.device)
    print(f"eco: {source.name}, {total / 60:.1f} min, working in {work.name}/ on {device}")

    # 1. Soundtrack -------------------------------------------------------------------------
    soundtrack = work / "audio.wav"
    if not soundtrack.exists():
        step("Extracting the soundtrack")
        media.extract_audio(source, soundtrack)

    # 2. Voice and background ---------------------------------------------------------------
    if args.no_separate:
        vocals_path, background_path = soundtrack, None
    else:
        vocals_path, background_path = work / "vocals.wav", work / "background.wav"
        if not (vocals_path.exists() and background_path.exists()):
            step("Separating your voice from the music and sound effects (the slowest step)")
            models.separate(soundtrack, vocals_path, background_path, device, args.separator_model)

    # 3. Transcript -------------------------------------------------------------------------
    transcript_path = work / "transcript.json"
    if not transcript_path.exists():
        step("Transcribing")
        language, words = models.transcribe(vocals_path, args.whisper_model, device, args.source)
        lines = group_words(words, max_seconds=args.max_line)
        save_json(transcript_path, {"language": language, "lines": [asdict(line) for line in lines]})
    transcript = load_json(transcript_path)
    lines = [Line(**line) for line in transcript["lines"]]
    if not lines:
        raise EcoError("No speech was found in this video.")
    source_lang = args.source or transcript["language"]
    stem = source.stem
    (out_dir / f"{stem}.{source_lang}.srt").write_text(
        srt([(l.start, l.end, l.text) for l in lines]), encoding="utf-8"
    )
    if source_lang in targets:
        print(f"  (skipping {source_lang}: the video is already in {language_name(source_lang)})")
        targets.remove(source_lang)
    if not targets:
        return 0

    # 4. Translation ------------------------------------------------------------------------
    translator: Translator | None = None

    def get_translator() -> Translator:
        nonlocal translator
        if translator is None:
            if args.translator == "claude":
                translator = Claude([asdict(l) for l in lines], args.notes, args.claude_model)
            else:
                translator = Argos()
        return translator

    for lang in targets:
        path = work / f"{lang}.json"
        previous = {entry["id"]: entry for entry in load_json(path).get("lines", [])}
        todo = [l for l in lines if l.id not in previous or previous[l.id]["source"] != l.text]
        if todo:
            step(f"Translating {len(todo)} lines into {language_name(lang)}")
            translated = get_translator().translate(
                [{"id": l.id, "text": l.text, "seconds": l.duration} for l in todo], source_lang, lang
            )
        else:
            translated = {}
        entries = []
        for l in lines:
            if l.id in translated:
                entries.append({"id": l.id, "start": l.start, "end": l.end, "source": l.text,
                                "text": translated[l.id], "machine": translated[l.id], "take": 1})
            else:
                entry = previous[l.id]
                entry.update(start=l.start, end=l.end)
                entries.append(entry)
        save_json(path, {"language": lang, "lines": entries})

    if args.review:
        step("Ready for review")
        print(f"Read and fix the translations in {work}/, one file per language:")
        for lang in targets:
            print(f"  {work / (lang + '.json')}   (edit the \"text\" of any line)")
        print("You can also fix recognition mistakes in transcript.json; changed lines are translated again.")
        print("Then run the same command without --review.")
        return 0

    # 5. Voice samples ----------------------------------------------------------------------
    step(f"Loading the {args.engine} voice")
    print(f"  {engine_class.license_note}")
    engine = models.load_engine(args.engine, device, args.exaggeration, args.cfg_weight, args.temperature)
    settings = [args.engine, args.exaggeration, args.cfg_weight, args.temperature]

    vocals, vocals_sr = sf.read(str(vocals_path), dtype="float32", always_2d=True)
    vocals = audio.to_mono(vocals)
    refs = work / "samples"
    refs.mkdir(exist_ok=True)

    def sample_from(indices: list[int]) -> Path:
        spans = [(lines[i].start, lines[i].end) for i in indices]
        path = refs / f"{digest(spans)}.wav"
        if not path.exists():
            gap = np.zeros(int(0.15 * vocals_sr), dtype=np.float32)
            pieces = []
            for start, end in spans:
                pieces += [vocals[int(max(start - 0.05, 0) * vocals_sr): int((end + 0.05) * vocals_sr)], gap]
            sf.write(str(path), np.concatenate(pieces[:-1]), vocals_sr)
        return path

    if args.voice_sample:
        steady_sample = Path(args.voice_sample).expanduser().resolve()
        if not steady_sample.exists():
            raise EcoError(f"{steady_sample} does not exist.")
    elif args.style == "steady":
        steady_sample = sample_from(voice_sample_indices(lines))
    else:
        steady_sample = None

    def sample_for(index: int) -> Path:
        return steady_sample or sample_from(reference_indices(lines, index))

    # 6. Dubbing ----------------------------------------------------------------------------
    background = None
    if background_path is not None:
        background_48k = work / "background.48k.wav"
        if not background_48k.exists():
            media.convert(background_path, background_48k, MIX_SR, channels=2)
        background, _ = sf.read(str(background_48k), dtype="float32", always_2d=True)
    length = int(total * MIX_SR)
    finished = []

    for lang in targets:
        step(f"Dubbing into {language_name(lang)}")
        path = work / f"{lang}.json"
        entries = load_json(path)["lines"]
        clips_dir = work / "clips" / lang
        clips_dir.mkdir(parents=True, exist_ok=True)

        def voice(entry: dict, index: int) -> np.ndarray:
            """The line spoken in your voice at 48 kHz, silence trimmed. Cached by its text."""
            sample = sample_for(index)
            key = digest(settings, lang, entry["text"], sample.name, entry.get("take", 1))
            clip48 = clips_dir / f"{entry['id']}-{key}.wav"
            if not clip48.exists():
                raw, first = clips_dir / "raw.wav", clips_dir / "first.wav"
                first.unlink(missing_ok=True)
                for attempt in range(3):
                    engine.synthesize(entry["text"], lang, sample, raw)
                    seconds = sf.info(str(raw)).duration
                    if models.plausible(seconds, entry["text"]):
                        break
                    print(f"  line {entry['id']}: take {attempt + 1} came out {seconds:.1f}s long, trying again")
                    if not first.exists():
                        raw.replace(first)
                else:  # three odd takes: keep the first rather than stop the whole dub
                    first.replace(raw)
                first.unlink(missing_ok=True)
                media.convert(raw, clip48, MIX_SR)
                for stale in clips_dir.glob(f"{entry['id']}-*.wav"):
                    if stale != clip48:
                        stale.unlink()
            clip, _ = sf.read(str(clip48), dtype="float32")
            return audio.trim_silence(clip, MIX_SR)

        clips = []
        for n, entry in enumerate(entries):
            clips.append(voice(entry, n))
            print(f"  {n + 1}/{len(entries)}  {entry['text'][:70]}", flush=True)

        starts = [e["start"] for e in entries]
        durations = [len(c) / MIX_SR for c in clips]
        long_lines = timing.too_long(starts, durations, total, args.max_speed)
        # Only lines the translator wrote are rephrased; a line you edited by hand stays yours.
        long_lines = [i for i in long_lines if entries[i]["text"] == entries[i].get("machine")]
        if long_lines and not args.no_shorten and get_translator().can_shorten:
            print(f"  {len(long_lines)} lines run too long to stay in sync; asking for shorter wording")
            windows = timing.windows(starts, total)
            shorter = get_translator().shorten(
                [{"id": entries[i]["id"], "original": entries[i]["source"], "translation": entries[i]["text"],
                  "seconds": windows[i], "spoken_seconds": durations[i]} for i in long_lines],
                source_lang, lang,
            )
            for i in long_lines:
                entry = entries[i]
                entry["text"] = entry["machine"] = shorter[entry["id"]]
                clips[i] = voice(entry, i)
                durations[i] = len(clips[i]) / MIX_SR
            save_json(path, {"language": lang, "lines": entries})

        placements = timing.plan(starts, durations, total, args.max_speed)
        placed = []
        tmp_in, tmp_out = clips_dir / "fit-in.wav", clips_dir / "fit-out.wav"
        for entry, clip, placement in zip(entries, clips, placements):
            if placement.speed > 1.001:
                sf.write(str(tmp_in), clip, MIX_SR, subtype="FLOAT")
                media.convert(tmp_in, tmp_out, MIX_SR, speed=placement.speed)
                clip, _ = sf.read(str(tmp_out), dtype="float32")
            original = vocals[int(entry["start"] * vocals_sr): int(entry["end"] * vocals_sr)]
            clip = audio.match_level(clip, MIX_SR, audio.speech_rms(original, vocals_sr))
            placed.append((placement.start, clip))
        for tmp in (tmp_in, tmp_out):
            tmp.unlink(missing_ok=True)
        late = sum(1 for p, e in zip(placements, entries) if p.start - e["start"] > 0.5)
        if late:
            print(f"  note: {late} lines start more than half a second late; shorter wording in "
                  f"{path.name} fixes that")

        mixed = audio.mix(audio.build_track(placed, length, MIX_SR), background)
        mix_path = work / f"{lang}.mix.wav"
        sf.write(str(mix_path), mixed, MIX_SR, subtype="FLOAT")

        name = f"{stem}.{lang}"
        media.export_audio(mix_path, out_dir / f"{name}.m4a", lang)
        finished.append(out_dir / f"{name}.m4a")
        if has_video:
            media.export_video(source, mix_path, out_dir / f"{name}.mp4", lang)
            finished.append(out_dir / f"{name}.mp4")
        (out_dir / f"{name}.srt").write_text(
            srt([(e["start"], e["end"], e["text"]) for e in entries]), encoding="utf-8"
        )
        finished.append(out_dir / f"{name}.srt")

    step("Done")
    for f in finished:
        print(f"  {f}")
    print(f"\nTo change a line: edit its \"text\" in {work.name}/<language>.json and run the same command.")
    print('For a new take of a line without changing it, raise its "take" number.')
    return 0

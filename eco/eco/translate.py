"""Translating the script line by line, for speaking rather than reading.

Claude is the default: it sees the whole transcript, keeps the creator's tone and slang, and
can be asked to keep each line about as long to say as the original, which is what keeps a
dub in sync. Argos Translate is the free, offline alternative: literal, line by line, but no
account or internet needed once its language packs are downloaded.
"""

from __future__ import annotations

import json
import os

LANGUAGE_NAMES = {
    "ar": "Arabic", "cs": "Czech", "da": "Danish", "de": "German", "el": "Greek", "en": "English",
    "es": "Spanish", "fi": "Finnish", "fr": "French", "he": "Hebrew", "hi": "Hindi", "hu": "Hungarian",
    "it": "Italian", "ja": "Japanese", "ko": "Korean", "ms": "Malay", "nl": "Dutch", "no": "Norwegian",
    "pl": "Polish", "pt": "Portuguese", "ru": "Russian", "sv": "Swedish", "sw": "Swahili",
    "tr": "Turkish", "zh": "Chinese (Mandarin)",
}


def language_name(code: str) -> str:
    return LANGUAGE_NAMES.get(code, code)


class TranslationError(RuntimeError):
    pass


class Translator:
    can_shorten = False

    def translate(self, lines: list[dict], source: str, target: str) -> dict[int, str]:
        """lines are {"id", "text", "seconds"}; returns {id: translated text}."""
        raise NotImplementedError

    def shorten(self, lines: list[dict], source: str, target: str) -> dict[int, str]:
        """lines are {"id", "original", "translation", "seconds", "spoken_seconds"}."""
        raise NotImplementedError


# ---------------------------------------------------------------------------------------------
# Claude

SYSTEM = """You translate the spoken script of a video so it can be dubbed in the creator's own \
cloned voice. Your text is read aloud by a voice generator and placed at the same timestamps as \
the original line, so write for the ear:

- Say it the way a native {target} speaker would say it in a video like this one: same register, \
energy, humour and warmth as the original. Casual stays casual; slang becomes the equivalent \
slang, not a dictionary word.
- Keep each line about as long to say as the original. Its duration in seconds is given. When a \
literal translation would run long, choose the shorter natural phrasing.
- Translate each line under its own id. Never move words between lines, merge lines or split \
them. When a sentence continues across lines, make each part sound natural spoken on its own.
- Keep names, brands, channel names, and terms viewers would say in the original language \
unchanged.
- The voice reads text literally: write numbers, units, symbols and abbreviations the way they \
should be pronounced in {target} ("25 km/h" becomes the words for it).
- Output only what is spoken: no notes, brackets, stage directions or added quotation marks.
{notes}
The full transcript, in {source}, so you know what the video is about. Lines are \
[id] (start-end seconds) text:

{transcript}"""

TRANSLATE = """Translate these lines into {target}. Return every id exactly once.

{lines}"""

SHORTEN = """These {target} lines take too long to say in the time the original line had, so \
the dub would fall out of sync. Rewrite each one shorter, aiming for about {ratio}% of its \
current length or less, keeping the meaning and the tone. Drop filler before meaning. Return \
every id exactly once.

{lines}"""

SCHEMA = {
    "type": "object",
    "properties": {
        "lines": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {"id": {"type": "integer"}, "text": {"type": "string"}},
                "required": ["id", "text"],
                "additionalProperties": False,
            },
        }
    },
    "required": ["lines"],
    "additionalProperties": False,
}

BATCH = 60  # lines per request; the transcript itself is cached, so batches stay cheap


class Claude(Translator):
    can_shorten = True

    def __init__(self, transcript: list[dict], notes: str, model: str):
        import anthropic

        self.anthropic = anthropic
        self.client = anthropic.Anthropic(max_retries=5)
        self.model = model
        self.notes = f"\nNotes from the creator about this video: {notes.strip()}\n" if notes.strip() else ""
        self.transcript = "\n".join(
            f"[{line['id']}] ({line['start']:.1f}-{line['end']:.1f}) {line['text']}" for line in transcript
        )

    def _system(self, source: str, target: str) -> list[dict]:
        text = SYSTEM.format(
            source=language_name(source), target=language_name(target), notes=self.notes, transcript=self.transcript
        )
        return [{"type": "text", "text": text, "cache_control": {"type": "ephemeral"}}]

    def _ask(self, system: list[dict], prompt: str, ids: set[int]) -> dict[int, str]:
        for attempt in range(2):
            with self.client.beta.messages.stream(
                model=self.model,
                max_tokens=32000,
                betas=["server-side-fallback-2026-07-01"],
                fallbacks="default",
                thinking={"type": "adaptive"},
                output_config={"effort": "high", "format": {"type": "json_schema", "schema": SCHEMA}},
                system=system,
                messages=[{"role": "user", "content": prompt}],
            ) as stream:
                response = stream.get_final_message()
            if response.stop_reason == "refusal":
                raise TranslationError("Claude declined to translate this script.")
            if response.stop_reason == "max_tokens":
                raise TranslationError("The translation was cut off; try again with fewer lines per video.")
            text = next(block.text for block in response.content if block.type == "text")
            result = {int(item["id"]): item["text"].strip() for item in json.loads(text)["lines"]}
            missing = ids - result.keys()
            if not missing:
                return {i: result[i] for i in ids}
            if attempt == 1:
                raise TranslationError(f"Claude left out lines {sorted(missing)}.")
        raise AssertionError("unreachable")

    def translate(self, lines, source, target):
        system = self._system(source, target)
        out: dict[int, str] = {}
        for i in range(0, len(lines), BATCH):
            batch = lines[i : i + BATCH]
            listing = "\n".join(f"[{l['id']}] ({l['seconds']:.1f}s) {l['text']}" for l in batch)
            prompt = TRANSLATE.format(target=language_name(target), lines=listing)
            out.update(self._ask(system, prompt, {l["id"] for l in batch}))
            print(f"  {min(i + BATCH, len(lines))}/{len(lines)} lines")
        return out

    def shorten(self, lines, source, target):
        system = self._system(source, target)
        worst = max(l["spoken_seconds"] / max(l["seconds"], 0.1) for l in lines)
        ratio = max(int(100 / worst), 50)
        listing = "\n".join(
            f"[{l['id']}] {l['seconds']:.1f}s available, currently {l['spoken_seconds']:.1f}s spoken\n"
            f"  original: {l['original']}\n  current: {l['translation']}"
            for l in lines
        )
        prompt = SHORTEN.format(target=language_name(target), ratio=ratio, lines=listing)
        return self._ask(system, prompt, {l["id"] for l in lines})


# ---------------------------------------------------------------------------------------------
# Argos Translate (offline)


class Argos(Translator):
    def __init__(self):
        # Each line is translated on its own, so Argos needs no sentence splitting; MiniSBD
        # keeps it off Stanza, which would run PyTorch next to CTranslate2 (on Intel Macs
        # that pairing stalled the CI dub). Read by argostranslate when it is imported.
        os.environ.setdefault("ARGOS_CHUNK_TYPE", "MINISBD")
        import argostranslate.package
        import argostranslate.translate

        self.package = argostranslate.package
        self.engine = argostranslate.translate

    def _install(self, source: str, target: str) -> None:
        installed = {(p.from_code, p.to_code) for p in self.package.get_installed_packages()}
        wanted = [(source, target)] if source == "en" or target == "en" else [(source, "en"), ("en", target)]
        if all(pair in installed for pair in wanted) or (source, target) in installed:
            return
        print(f"  Downloading Argos language packs for {source} -> {target}...")
        self.package.update_package_index()
        available = {(p.from_code, p.to_code): p for p in self.package.get_available_packages()}
        if (source, target) in available:
            wanted = [(source, target)]
        for pair in wanted:
            if pair not in available:
                raise TranslationError(f"Argos Translate has no {pair[0]} -> {pair[1]} language pack.")
            self.package.install_from_path(available[pair].download())

    def translate(self, lines, source, target):
        self._install(source, target)
        return {l["id"]: self.engine.translate(l["text"], source, target) for l in lines}

# Eco

**Dub your videos into other languages, in your own voice.** Eco listens to your video, writes
down what you say, translates it the way a native speaker would say it, and speaks it again in
your cloned voice, line by line, at the same moments, over your original music. You get a
finished video per language, the audio track on its own for YouTube's multi-language audio,
and subtitles.

```
eco my-video.mp4 --to es,fr,pt
```
```
my-video.es.mp4   my-video.es.m4a   my-video.es.srt
my-video.fr.mp4   my-video.fr.m4a   my-video.fr.srt
my-video.pt.mp4   my-video.pt.m4a   my-video.pt.srt
my-video.en.srt   (the original, as subtitles)
```

Everything runs on your own computer, for free, with no account or API key. (If you have an
Anthropic API key, Claude can do the translation instead, with better wording.)

## Eco for Mac

**One download, nothing else to set up:** no Terminal, no API key, no accounts.

1. Download **`Eco-mac.zip`** from the
   [eco-mac-latest release](https://github.com/fablab503-collab/fablab-tools/releases/tag/eco-mac-latest),
   unzip it and drag **Eco** to Applications.
2. Open it. The first time, Eco gets itself ready on its own: one progress bar, about
   15–30 minutes, about 7 GB. You can leave it running.
3. Drop in a video, tick the languages, press **Dub**. That's it.

Tick *"Let me read and fix the translation before it is spoken"* if you want to check the
lines first.

**If macOS says it cannot check Eco for malicious software:** open System Settings › Privacy &
Security, scroll down to "Eco was blocked" and click **Open Anyway** (only once). This goes away
for good once the app is signed with an Apple Developer ID
([how](#signing-and-testing-the-mac-app-for-whoever-publishes-it)).

**Which Mac?** One app for both. macOS 13 Ventura or later.

| | Apple silicon (M1, M2, M3, M4…) | Intel |
|---|---|---|
| Voice | Chatterbox, on the graphics chip | XTTS, on the processor |
| A 10-minute video, per language | roughly 10–25 minutes | roughly 1–2 hours |
| Channels that earn money | Yes | **No**: the Intel voice is for personal videos only |
| Languages | 23 | 17 |

(Intel Macs cannot run Chatterbox: it needs PyTorch 2.6, and PyTorch's last release for Intel
Macs is 2.2. The app picks the right voice by itself and asks Intel users once to confirm the
personal-use limit.)

Everything Eco downloads goes into `~/Library/Application Support/Eco`; deleting that folder
and the app removes Eco completely. To build the app yourself: `eco/mac/build.sh --install`.

## How it works

| Step | What happens | Model |
|---|---|---|
| 1. Separate | Your voice is split from the music and sound effects | Demucs (htdemucs_ft) |
| 2. Transcribe | Every word you say, with its timing | Whisper large-v3 |
| 3. Cut into lines | Words are regrouped into spoken sentences, at most 12 s each | |
| 4. Translate | Offline by default; with an API key, Claude translates the whole script at once, so tone, jokes and context carry over, keeping each line about as long to say as yours | Argos Translate, or Claude |
| 5. Voice | Each line is spoken in your cloned voice, from a sample of **that same line** in the original, so its energy and emotion carry over | Chatterbox Multilingual, or XTTS-v2 |
| 6. Fit | A line that runs long uses the pause after it, then is sped up (up to 1.25×, pitch unchanged); with Claude, a line that still does not fit is rewritten shorter and voiced again | |
| 7. Mix | Each line is matched to the loudness of your original line (a whisper stays a whisper), laid over the original music, and put back on the untouched picture | ffmpeg |

## Signing and testing the Mac app (for whoever publishes it)

**Making macOS trust the app.** macOS opens an app without any warning only when it is signed
with an Apple *Developer ID* certificate and notarised by Apple. That needs the Apple Developer
Program membership (the one Bouclier is published with); nothing in the code can replace it.
Once these five repository secrets exist (GitHub › Settings › Secrets and variables › Actions),
every build is signed and notarised automatically:

| Secret | What it is |
|---|---|
| `MACOS_CERT_P12` | A **Developer ID Application** certificate with its private key, exported from Keychain Access as .p12, then `base64 -i cert.p12 \| pbcopy` |
| `MACOS_CERT_PASSWORD` | The password chosen when exporting the .p12 |
| `ASC_KEY_P8` | An App Store Connect API key (Users and Access › Integrations › Keys, "Developer" access), `base64 -i AuthKey_XXXX.p8 \| pbcopy` |
| `ASC_KEY_ID` | That key's ID |
| `ASC_ISSUER_ID` | The Issuer ID shown above the keys list |

To create the certificate: Xcode › Settings › Accounts › Manage Certificates › + › Developer ID
Application. Building on your own Mac works the same way: `SIGN_IDENTITY=… NOTARY_KEY=…
NOTARY_KEY_ID=… NOTARY_ISSUER=… ./build.sh` (see the top of `mac/build.sh`).

**How the app is tested:** every change is built on GitHub's Apple silicon and Intel Macs, the
engine is installed on each exactly as the app does it, and a spoken clip is dubbed from English
to Spanish end to end. The test dubs can be downloaded from each run's page to listen to.

## Using it from the command line

On Windows, Linux, or a Mac without the app.

### What you need

- **A computer with an NVIDIA graphics card (6 GB or more) is strongly recommended.** A
  10-minute video takes roughly 10-20 minutes per language on a gaming GPU. It works on a Mac
  (Apple Silicon) or with no GPU at all, just several times slower.
- **Python 3.11** ([python.org](https://www.python.org/downloads/), or
  [Miniconda](https://docs.conda.io/en/latest/miniconda.html)) and **ffmpeg**
  (Windows: `winget install ffmpeg`, Mac: `brew install ffmpeg`, Linux: `sudo apt install ffmpeg`).
- About **15 GB of free disk** for the models, which download on first use.
- Optional: an **Anthropic API key** from [console.anthropic.com](https://console.anthropic.com/)
  for better translations (a few cents per video). Without one, Eco translates offline.

### Install

```bash
git clone https://github.com/fablab503-collab/fablab-tools.git
cd fablab-tools/eco
python3.11 -m venv .venv
source .venv/bin/activate          # Windows: .venv\Scripts\activate

# NVIDIA GPU only: install PyTorch with CUDA first, or you will get the slow CPU version
pip install torch==2.6.0 torchaudio==2.6.0 --index-url https://download.pytorch.org/whl/cu124

pip install -e ".[chatterbox]"
```

Optional, for translation by Claude instead of the offline translator:

```bash
export ANTHROPIC_API_KEY=sk-ant-...          # Windows PowerShell: $env:ANTHROPIC_API_KEY="sk-ant-..."
```

### Use

**The recommended way, with a review step:**

```bash
eco my-video.mp4 --to es,fr --review --notes "cycling vlog, casual and funny, keep bike part names in English"
```

This stops after translating. Open `my-video.eco/es.json` and read the `"text"` of each line;
fix anything that sounds wrong. If Whisper misheard you, fix the line in
`my-video.eco/transcript.json` and it is translated again. Then run the same command without
`--review`:

```bash
eco my-video.mp4 --to es,fr --notes "cycling vlog, casual and funny, keep bike part names in English"
```

**Everything is remembered** in the `my-video.eco/` folder. Running the command again only
redoes what changed: edit a line's text and only that line is voiced again. If a line sounds off
but the words are right, raise its `"take"` number in the language file for a fresh take.

### Getting the best quality

- **Record clean audio.** The clone is made from your voice in the video; a good microphone
  close to your mouth matters more than any setting here. Music is removed first, but echo and
  wind are not.
- **Use `--notes`** to tell the translator who you are talking to and how: "kids' channel",
  "formal tutorial", "use regional slang where I do".
- **Pick the target variety in the notes** when it matters: "Latin American Spanish",
  "Brazilian Portuguese", "European French".
- **If the dubbed voice has your accent** in the other language and you do not want it, try
  `--cfg-weight 0.3`. If it sounds too flat, `--exaggeration 0.7`; too theatrical, `0.35`.
- **If the voice drifts between lines**, use `--style steady` (one voice sample for the whole
  video), or give it a 10-20 second clean recording of yourself with `--voice-sample me.wav`.
- **If you talk fast**, allow a little more speed-up with `--max-speed 1.35`, or leave it and
  let Claude shorten the long lines (it does this automatically).
- **Videos without music** can skip separation, the slowest step: `--no-separate`.

### All options

```
eco VIDEO --to es,fr [--from en] [--out FOLDER] [--review] [--notes "..."]
    --engine chatterbox|xtts     voice engine (default chatterbox)
    --style line|steady          per-line tone (default) or one steady voice sample
    --voice-sample FILE          clone from this recording instead of the video
    --translator auto|argos|claude   offline Argos, or Claude when ANTHROPIC_API_KEY is set (default auto)
    --claude-model MODEL         default claude-opus-5
    --max-speed 1.25             how much a long line may be sped up
    --no-shorten                 never rephrase long lines
    --exaggeration 0.5           chatterbox emotion intensity
    --cfg-weight 0.5             chatterbox pacing / accent
    --temperature T              voice randomness
    --device auto|cuda|mps|cpu
    --whisper-model large-v3     or large-v3-turbo (faster), medium, small
    --separator-model htdemucs_ft   or htdemucs (4x faster, a little less clean)
    --no-separate                skip voice/music separation
    --max-line 12                longest line in seconds
```

### Languages

**Chatterbox** (default, 23): Arabic, Chinese, Danish, Dutch, English, Finnish, French,
German, Greek, Hebrew, Hindi, Italian, Japanese, Korean, Malay, Norwegian, Polish, Portuguese,
Russian, Spanish, Swahili, Swedish, Turkish.

**XTTS-v2** (17): Arabic, Chinese, Czech, Dutch, English, French, German, Hindi, Hungarian,
Italian, Japanese, Korean, Polish, Portuguese, Russian, Spanish, Turkish.

Your video can be in any language Whisper understands (around 100).

## Which voice engine?

| | Chatterbox (default) | XTTS-v2 |
|---|---|---|
| Licence | **MIT: fine for monetised channels** | **Non-commercial only** |
| Languages | 23 | 17 (adds Czech, Hungarian) |
| Emotion control | Yes (`--exaggeration`) | No |
| Watermark | Inaudible PerTh watermark marks the audio as AI-generated | None |

XTTS-v2 sounds very natural, but its licence (Coqui Public Model License) forbids commercial
use, and a monetised YouTube channel is commercial. Coqui has shut down, so no commercial
licence can be bought. Use it only on channels that make no money. The two engines need
different versions of PyTorch, so install XTTS in its own environment:

```bash
python3.11 -m venv .venv-xtts && source .venv-xtts/bin/activate
pip install -e ".[xtts]"
eco my-video.mp4 --to cs --engine xtts
```

## Putting it on YouTube

**One video, several languages (best).** In YouTube Studio open the video, go to
**Languages → Add language**, and under **Dub** upload `my-video.es.m4a`. Viewers get the audio
in their language automatically, and the views all count on one video. The multi-language audio
feature has been rolling out gradually; if you do not see it yet, use the second way.

**One video per language.** Upload `my-video.es.mp4` as its own video, ideally on a channel
for that language.

**Subtitles.** Under **Languages → Add language → Subtitles**, upload the `.srt` files.

**Tell viewers.** YouTube asks creators to disclose realistic AI-generated or altered content
when they upload; check the altered-content setting, and a line in the description such as
"Dubbed with an AI clone of my own voice" keeps things honest with your audience.

## Your voice, and other people's

Only clone your own voice, or the voice of someone who has agreed to it. If there are other
people talking in your video, Eco will clone them too; get their permission, or dub only your
own videos. Cloning someone's voice without consent can be illegal where you live, and it is
against YouTube's rules.

## Offline and free

With `--translator argos` nothing leaves your computer and nothing costs money after the models
are downloaded (`pip install -e ".[argos]"`). The translations are literal, line by line, and
are not shortened to fit, so review them with `--review` and trim long lines by hand.

## Troubleshooting

- **"ffmpeg was not found"**: install ffmpeg and open a new terminal.
- **It is very slow**: check it says `on cuda` at the start. If it says `on cpu` with an NVIDIA
  card, reinstall PyTorch with the CUDA command above.
- **Out of GPU memory**: use `--separator-model htdemucs` and `--whisper-model large-v3-turbo`.
- **A line is spoken wrong or garbled**: fix or simplify its text in the language file, or raise
  its `"take"`.
- **Stopped halfway**: run the same command again; it carries on where it stopped.
- **Start over for one language**: delete `my-video.eco/es.json`. To start over completely,
  delete the whole `my-video.eco/` folder.

## Tests

```bash
pip install numpy soundfile pytest
python -m pytest
```

The tests run the whole pipeline with real ffmpeg and stand-ins for the models, so they need no
GPU and download nothing.

## Licence

Eco is MIT licensed. The models it downloads keep their own licences, listed in
[THIRD_PARTY.md](THIRD_PARTY.md).

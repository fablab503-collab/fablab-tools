# Third-party components

Eco's own code is MIT licensed. It downloads and runs the following, each under its own licence.
Check each project's page for the current terms before relying on this summary.

| Component | Used for | Licence | Commercial use |
|---|---|---|---|
| [Chatterbox](https://github.com/resemble-ai/chatterbox) (Resemble AI), code and weights | Default voice engine | MIT | Yes |
| [Coqui XTTS-v2](https://huggingface.co/coqui/XTTS-v2), via [coqui-tts](https://github.com/idiap/coqui-ai-TTS) | Optional voice engine | Weights: Coqui Public Model License; code: MPL 2.0 | **No** |
| [Whisper](https://github.com/openai/whisper) (OpenAI), via [faster-whisper](https://github.com/SYSTRAN/faster-whisper) | Transcription | MIT | Yes |
| [Demucs](https://github.com/adefossez/demucs) | Separating voice from music | MIT | Yes |
| [Argos Translate](https://github.com/argosopentech/argos-translate) | Optional offline translation | MIT (language packs have their own licences) | Yes |
| [Anthropic Python SDK](https://github.com/anthropics/anthropic-sdk-python) | Translation with Claude | MIT | Yes; use of Claude is under Anthropic's terms |
| [FFmpeg](https://ffmpeg.org/) | Reading and writing audio and video | LGPL / GPL, installed separately | Yes |

Audio made with Chatterbox carries Resemble AI's inaudible PerTh watermark, which lets
detection tools recognise it as AI-generated.

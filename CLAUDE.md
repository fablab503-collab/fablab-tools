# CLAUDE.md - fablab-tools

Free, open-source apps from FabLab 503 (VeloTrack, Bouclier), built with Claude Code and shipped
from GitHub Actions. `README.md` has the layout, `CONTRIBUTING.md` the rules. Builds run on CI
only: there is no Android toolchain on the Mac this is developed from.

## Skills - two kinds, in two places

**1. This repository's own skills** live in `.claude/skills/` and load on their own in every
Claude Code session on this repository, web sessions included: `idea-to-beta`, `build-1`,
`android-app-2`, `shipping-and-iterating`, `google-play-listing`. They are the recipes this
project was built with. Use them when the task is building, shipping or listing an app from here.

**2. The big library** - 2,400+ procedural skills on cooking, cycling, photo and video, 3D,
software, iOS and Android apps, health, home and more - lives in another repository,
`fablab503-collab/second-brain`, folder `Skills/`. It is never copied here: it grows every night
there, and a copy would be stale by tomorrow. Recall exactly one skill when a how-do-I or
why-is-this-failing question comes up:

- **On the web** (a Claude Code cloud session): attach the repository first with `add_repo`
  (owner `fablab503-collab`, repo `second-brain`) and clone it where the tool says. Then
  `python3 <clone>/Skills/_scripts/recall.py "the problem in plain words"` and
  `python3 <clone>/Skills/_scripts/recall.py --show <domain>/<skill>` for one id.
- **On the Mac**: the vault is at `/Volumes/Volume1/SecondBrain`; run the same two commands there.

The `skills-recall` skill in `.claude/skills/` carries the protocol. For a whole job - build an
iOS, Android, Mac or cross-platform app, launch an AI video ad - the library also has
**playbooks**: `python3 <path>/Skills/_scripts/playbook.py "the job"` names one, `--show <slug>`
is the map of stages, `--stage <slug> <n>` loads one stage's skills; work it one stage at a time. Never read `Skills/` or its
`index.tsv` in bulk, and never install that library as skills - a search plus one skill costs
about 440 tokens, the library is over 900,000.

## Connectors

Higgsfield, GitHub and the other MCP connectors are attached to the account, not to this
repository, so they are available in every session without anything here.

# build-1 skill (repo copy)

Reusable process for shipping an app from spec to a GitHub Actions build and Release when the
development machine has no local toolchain. The copy in this repository, `.claude/skills/build-1/`, loads in every Claude Code session here (web sessions included); a copy in `~/.claude/skills/build-1/` on the Mac is optional
(SKILL.md + reference.md); this folder is the versioned backup with the heavy material:

- `reference/` — full research notes with source URLs (MapLibre, Android toolchain, Protomaps, GPS/battery).
- `templates/` — CI workflows, Gradle files, style/asset tooling, the research and implement Workflow
  scripts, and `ci.sh` (GitHub Actions status/log helper).
- `examples/` — the VeloTrack design spec and plan with binding module contracts.

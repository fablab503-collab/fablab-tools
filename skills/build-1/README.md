# build-1 skill (repo copy)

Reusable process for shipping an app from spec to a GitHub Actions build and Release when the
development machine has no local toolchain. The active copy lives in `~/.claude/skills/build-1/`
(SKILL.md + reference.md); this folder is the versioned backup with the heavy material:

- `reference/` — full research notes with source URLs (MapLibre, Android toolchain, Protomaps, GPS/battery).
- `templates/` — CI workflows, Gradle files, style/asset tooling, the research and implement Workflow
  scripts, and `ci.sh` (GitHub Actions status/log helper).
- `examples/` — the VeloTrack design spec and plan with binding module contracts.

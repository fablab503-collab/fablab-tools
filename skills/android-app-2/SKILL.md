---
name: android-app-2
description: Use when extending or rebuilding VeloTrack, or building another full-featured offline Android app end to end from this Mac (spec → parallel implementation → GitHub Actions APK → emulator verification), including Material 3 restyles and offline map data features; complements build-1, which holds the toolchain pipeline.
---

# Android app 2: the complete VeloTrack job, as a repeatable recipe

## Overview

Everything done on 2026-09-07 to take a voice brief ("low-battery offline bike GPS app") to a
Material 3 Android app with in-app map downloads, built only on GitHub Actions and verified on the
Android emulator from this Mac. **REQUIRED SUB-SKILL:** `build-1` for the toolchain facts, CI
templates and sandbox workarounds. This skill adds the product and process knowledge on top.

## When to use

- "Add a feature to VeloTrack", "make the bike app do X", "restyle the app", "download maps".
- A new app of the same shape: Android, Kotlin Views + ViewBinding, Material 3, MapLibre, no Play
  Services, sideloaded APK from GitHub Releases.

## The loop that worked (repeat per feature)

1. **Research first when facts matter** (versions, library behaviour, format specs): 2–4 Workflow
   agents with WebFetch, structured results saved to files agents can read later.
2. **Brief with binding contracts**: `docs/superpowers/specs/<date>-<feature>-design.md` with decisions
   + why, exact Kotlin signatures per module, view ids/classes, string/drawable names other modules
   rely on, and an acceptance list. Agents implement against it in parallel without a compiler.
3. **Implement workflow**: one agent per module (own files only), then 3 read-only reviewers
   (compile/contracts, resources/build, logic/protocol), then one fixer per module that verifies each
   finding first. Filter "minor" findings and apply them yourself afterwards.
4. **Push → CI → fix from `e:` lines** (`templates/ci.sh` in build-1). First-try green happened on 4 of
   5 features once contracts were explicit.
5. **Emulator verification** via `Control your Mac` osascript + adb: install, screenshot every screen,
   simulate GPS, exercise the new flow end to end, read logcat for the package. Fix what you see and
   push again. Update memory + skill notes.

## Product facts (VeloTrack)

| Area | Decision |
|---|---|
| Map | MapLibre `android-sdk-opengl` 13.6.0, Protomaps dark flavour patched to black earth, four MBTiles band files (z0–6, 7–9, 10–12, 13–15) as separate sources, `crossSourceCollisions(false)` |
| Data | In-app PMTiles range extraction from `build.protomaps.com/<YYYYMMDD>.pmtiles`; exact size before download; presets 10 km / 100 km / 1000 km / World / France; `:download` dataSync service; regions table |
| GPS | GPS_PROVIDER only via LocationManagerCompat; PointFilter (accuracy 50 m, jump filter, 5 m thinning, auto-pause 1.0/1.5 m/s, derived speed when receiver reports 0) |
| Recording | Foreground `location` service, SQLite (WAL, one shared helper), GPX 1.1 export via SAF/FileProvider, crash recovery dialog |
| UI | Material You dynamic colour (Google-blue baseline), HUD card + status chip, left column of 56 dp surface FABs, primary Extended FAB bottom-right, M3 two-line lists, edge-to-edge insets |
| Privacy | INTERNET only for the Download screen; `MapLibre.setConnected(false)`; CI greps for it |

## Gotchas discovered on device

- Emulator GPS reports speed 0 and no bearing → derive speed from displacement; heading holds.
- `adb install -r` kills a running recording → recovery dialog appears (expected).
- Same-JSON `setStyle` reuses sources and their cached empty tiles → pass through a blank style.
- Two SQLite libraries in one process (MapLibre + Android) → writer in its own process.
- Someone may tap the emulator while you test; check logcat `GrantPermissionsViewModel` /
  `ActivityTaskManager START` before blaming the app.
- Android 15+ edge-to-edge: pad HUD/controls with window insets.

## Where things are

- Repo `~/Documents/GitHub/fablab-tools`, app `velotrack/`, specs/plans under `docs/superpowers/`,
  releases `velotrack-latest` (rolling) and `velotrack-v<run>`; test map `map-mountain-view-test`.
- Skill copies with templates/research: `fablab-tools/skills/build-1/`, `fablab-tools/skills/android-app-2/`.
- Emulator helpers: `skills/build-1/templates/emulator-ride.sh`; PMTiles dry-run `velotrack/tools/pmtiles_dryrun.py`.

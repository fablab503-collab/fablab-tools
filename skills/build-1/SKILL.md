---
name: build-1
description: Use when the user asks to build and publish an app (especially Android) from this Mac, which has no Java or Android SDK, so the APK must be compiled and released through GitHub Actions; also when resuming or extending the VeloTrack bike app, or starting any new "build me an app and put it on GitHub" project in the fablab-tools repo.
---

# Build 1: spec → parallel implementation → GitHub Actions APK

## Overview

Ship a complete app from a Mac with no toolchain: **CI is the only compiler**, so verify every
version fact first, write binding module contracts, author modules in parallel, review before the
first push, and iterate from CI logs. Proven on VeloTrack (offline GPS bike app, Sept 2026): first
CI run compiled clean, 109 tests, APK published within one fix.

## When to use

- User says "build me an app and publish it to GitHub", "make an APK", "I can't install Android Studio".
- Working in `~/Documents/GitHub/fablab-tools` (repo `fablab503-collab/fablab-tools`).
- Any offline map / GPS / low-battery Android app (reuse the verified stack below).

Not for: iOS (needs Xcode + developer account), or projects where the user already has a local
toolchain and wants interactive iteration.

## The pipeline

1. **Environment.** `gh` API calls fail in the sandbox with a TLS error, but `gh auth token` works.
   Use `curl -H "Authorization: Bearer $(gh auth token)" https://api.github.com/...` and plain git
   over HTTPS (`credential.helper=!gh auth git-credential`). Ignore "failed to store: 100001".
2. **Research workflow.** Never trust remembered versions. Fan out 3–4 agents (Workflow tool,
   `effort: high`, WebFetch) to verify: build toolchain, map/graphics library, data pipeline,
   domain best practices. Save results to files agents can read later.
3. **Spec.** `docs/superpowers/specs/YYYY-MM-DD-<app>-design.md` with a "verified facts" table and an
   "assumptions made without the user" table (user often unavailable; state Android inferred, etc.).
4. **Plan with binding contracts.** `docs/superpowers/plans/…-plan.md`: exact Kotlin signatures per
   module, file ownership per module, shared resource names (R.string ids, intent extras). Write
   the shared files yourself (models, prefs, session singleton, Gradle scaffold) before fan-out.
5. **Implement workflow.** One agent per module, each owning listed files only, then 5 read-only
   reviewers (kotlin-compile, contracts, android-runtime, build-ci, logic-tests), then one fixer
   per module that verifies each finding before editing. Template: `templates/implement-workflow.js`.
6. **Push → CI → fix.** Poll with `templates/ci.sh status|wait|logs <sha|run>`; read `e:` lines
   and "What went wrong"; test reports come as an artifact. Expect 1–2 rounds.
7. **Verify the release assets exist** via the Releases API, then update memory and README.

## Quick reference (verified 2026-09-07; re-verify if >2 months old)

| Topic | Decision | Gotcha |
|---|---|---|
| AGP / Gradle / JDK | 9.4.0 / 9.7.1 via `gradle/actions/setup-gradle@v6 gradle-version` / Temurin 17 | AGP 9 compiles Kotlin itself: never apply `org.jetbrains.kotlin.android`. Unit tests exist only for debug: `gradle testDebugUnitTest assembleRelease`. No wrapper jar needed. |
| SDK levels | compileSdk 36, targetSdk 36, minSdk 26 | `androidx.core:core-ktx:1.19.0` demands compileSdk 37 → pin 1.18.0. |
| Map engine | `org.maplibre.gl:android-sdk-opengl:13.6.0` | Default `android-sdk` is Vulkan-only since 13.0 and refuses to install on non-Vulkan GPUs. |
| Offline tiles | MBTiles via `"url": "mbtiles:///abs/path"`; PMTiles only secondary | MapLibre bugs #4462/#4459/#4421 break gzip PMTiles (all Protomaps extracts). Neither format loads from APK assets. |
| Map data | Protomaps planet `https://build.protomaps.com/<YYYYMMDD>.pmtiles`, `pmtiles extract --bbox`, then `pip install pmtiles==3.7.0 && pmtiles-convert x.pmtiles x.mbtiles` | Index: `build-metadata.protomaps.dev/builds.json`. Release asset limit 2 GB. |
| Style/fonts | `@protomaps/basemaps@5.7.2` `layers("protomaps", namedFlavor("dark"), {lang})`, glyphs/sprites from `protomaps/basemaps-assets` as `asset://` | No `lang` → no labels. Patch background/earth to #000000 for OLED. |
| No network | Manifest `tools:node="remove"` on INTERNET + ACCESS_WIFI_STATE; `MapLibre.setConnected(false)`; CI greps merged manifest | Keep ACCESS_NETWORK_STATE (normal permission). |
| GPS | `LocationManagerCompat` + `LocationRequestCompat(1000)` GPS_PROVIDER only; FGS type `location`; fine location granted *before* `startForeground` | Battery Saver modes 1/2 kill GPS with screen off. No A-GPS offline → slow first fix. |
| Signing | PKCS12 from `openssl req … && openssl pkcs12 -export`, committed for a private repo, env-overridable | Key password must equal store password. |
| Release | Rolling pre-release `<app>-latest` + immutable `<app>-v<run>`; `gh release view || create`, `upload --clobber`; `permissions: contents: write` | Force-move the `latest` tag with `git tag -f && git push -f`. |
| Triggers | Build workflow: `push` to `main` filtered by `paths: [<app>/**, .github/workflows/<file>]` plus `workflow_dispatch`; map extract: `workflow_dispatch` with inputs | Tag pushes do not re-trigger a `branches: [main]` filter. `concurrency` group per workflow. |

## Testing on the Android emulator from this Mac

- The sandbox blocks `adb` from starting its server ("could not install smartsocket listener") and
  from connecting to one. Run adb through the `Control your Mac` osascript tool instead:
  `do shell script "…/platform-tools/adb devices"`. Each call must finish in under ~30 s and exit 0
  (append `; exit 0`); put longer sequences in a script under `/tmp/claude-501/…` and start it with
  `nohup … &`, then wait on its log with a background `until grep` loop.
- The user creates the AVD in Android Studio (Device Manager); no system images or cmdline-tools are
  installed otherwise. Emulator default position is Mountain View (37.422, -122.084); extract a
  test map around it. `adb push file.mbtiles /sdcard/Android/data/<pkg>/files/maps/` works and the
  app lists it without the picker.
- `adb emu geo fix <lon> <lat> <alt>` fixes report speed 0 and no bearing: speed must be derived
  from displacement or the ride auto-pauses; the course-up camera will not rotate.
- `adb install -r` kills a running recording: expect the recovery dialog on next launch.
- The user may be tapping the emulator at the same time; check logcat (`GrantPermissionsViewModel`,
  `ActivityTaskManager START`) before blaming the app for unexpected screens or duplicate rides.
- Android 15+ is edge-to-edge: pad HUD/controls with `ViewCompat.setOnApplyWindowInsetsListener`.

## Offline map data lessons (in-app downloads)

- MapLibre's MBTiles source returns "no content" for a missing tile and draws nothing (no parent
  fallback); it only overzooms past a source's `maxzoom`. Sparse coverage needs one file per zoom
  band (z0–6, 7–9, 10–12, 13–15), each its own style source with the layer set repeated.
- Reloading the same style JSON keeps unchanged sources and their cached empty tiles: pass through a
  blank style before the real one after the data changed (verified on device: World download →
  new area renders without restart).
- PMTiles range extraction on-device works: header (127 B) + root dir (≈16 KB) + a few leaf dirs,
  then only the tile blobs. Measured: 10 km z13–15 ≈ 16–50 MB, 100 km z10–12 ≈ 60 MB, France z7–9
  ≈ 100 MB, world z0–6 = 45 MB. Reference implementation: `velotrack/tools/pmtiles_dryrun.py`.
- Run the writer in a separate Android process (`android:process`) when MapLibre (own SQLite) reads
  the same file; keep band files in WAL mode; `dataSync` foreground service for the download.

## Workflow-script gotchas

- Shell text like `${GITHUB_RUN_NUMBER}` inside a JS template prompt is interpolated: write `\${…}`.
- Spread results null-safely: `.then(r => r ? {key, ...r} : null)` then `.filter(Boolean)`.
- "You've hit your session limit" kills agents; resume with `resumeFromRunId` after the reset.
- Agents share the filesystem: point them at absolute paths for plan, spec, research, scaffold.

## Common mistakes

- Letting reviewers "fix" by changing a contracted signature: fix call sites instead.
- Feeding a style with `"tiles": []` to MapLibre when no map is loaded: use a background-only style.
- Treating timestamp 0 as "unset" in stats code: use an explicit started flag.
- Starting CI iteration before a review pass: each CI round costs 5–8 minutes.

## Files

- `reference.md`: condensed verified facts and code snippets (MapLibre API, FGS, GPS, Protomaps).
- Full research notes, templates (workflows, Gradle, plan, implement workflow, ci.sh) and the
  VeloTrack spec/plan examples: `~/Documents/GitHub/fablab-tools/skills/build-1/` (versioned on GitHub).

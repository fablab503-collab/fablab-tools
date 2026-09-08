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
| Favourites | Home / Work / Favourites FABs at the top of the left column; `favorites` table (TrackDatabase v3) with kinds home/work/person/restaurant/theater/place, name + description; bottom sheet add/edit/delete; straight-line guidance = target marker + dashed line + HUD row "→ name · distance · direction" (relative sectors when heading known, compass point otherwise) |
| Riding mode | `ui/RidingModeController` (pure Kotlin): the whole `controls` container fades out above 5 km/h for 3 s, back below 5 km/h for 2 s or on any touch (`Activity.onUserInteraction`), re-hides 8 s after a touch; stale speed (> 5 s old) counts as slow |
| Theme | `theme_mode` Dark / Light / Auto; Auto = `geo/SolarTimes` sunrise/sunset from `Prefs.lastPosition` (07–19 fallback) applied by `ui/NightModeManager` via `AppCompatDelegate.setDefaultNightMode` in `VeloTrackApp` and re-checked on resume/at the next transition; `Theme.Material3.DayNight`, light palette in `values/colors.xml`, dark in `values-night/`; map picks `style_light.json` (Protomaps light flavour with darker casings/labels) via `MapController.lightMap` |
| Font | Barlow Condensed (OFL) in `res/font/barlow_condensed_*.ttf` + `barlow_condensed.xml` (400/500/600); theme `android:fontFamily` + all 15 `textAppearance*` overridden in `values/type.xml` (letterSpacing 0). The user's Interstate Comp OTF was used first, then removed before going public: commercial font, not redistributable |
| 3D icons | 105 "color" style icons converted with `cwebp -q 82 -alpha_q 100 -resize 320 0` into `assets/icons3d/` (~5 KB each); app subset `res/drawable-nodpi/img3d_*.webp` used as illustrations only (stats cards, empty states, no-map overlay) |
| Auto-record + totals | `ui/AutoRecordDetector` (≥ 3 fixes above 5 km/h spanning 10 s, 2-min cooldown after a stop, no dialogs/permission prompts on the auto path) starts the FGS from the resumed activity; `storage/StatsRepository` SUM/COUNT/MAX over finished tracks with java.time period starts; `StatsActivity` all-time / year / month / week cards |
| Idle speed | `location/IdleSpeedEstimator` derives speed from displacement when the receiver reports 0 (same noise gate as PointFilter) so riding mode / auto-record also work idle |
| Where am I | `MapController.placeNameAt(latLon)` = `queryRenderedFeatures` in an 18 px box around the puck on `roads_*_b3..b1`; then the rendered green polygon → park-like POI point inside it (`VectorSource.querySourceFeatures("pois", kind filter)` + point-in-polygon) or a generic kind label; then named water / locality. Throttled to every 2 s and > 10 m; shown under the stats with a 16 dp pin; hidden when null or after a style reload |

## Gotchas discovered on device

- Emulator GPS reports speed 0 and no bearing → derive speed from displacement; heading holds.
- `adb install -r` kills a running recording → recovery dialog appears (expected).
- Same-JSON `setStyle` reuses sources; even a blank-style pass-through does not clear MapLibre's
  cached "no content" tile answers or its open MBTiles connections → after a download the main
  activity calls `recreate()` (verified: fresh install → first download → map appears at once).
- Test the *first* download on a fresh install (`adb uninstall` first): the empty-band startup path
  behaves differently from a second download into files that already have tiles.
- Two SQLite libraries in one process (MapLibre + Android) → writer in its own process.
- Someone may tap the emulator while you test; check logcat `GrantPermissionsViewModel` /
  `ActivityTaskManager START` before blaming the app.
- Android 15+ edge-to-edge: pad HUD/controls with window insets.
- Selecting a chip can reflow a screen (chips wrap differently): re-screenshot before tapping a
  button by coordinates, or the tap lands on the wrong control.
- To prove coarse (z0–6) data renders at riding zoom, put the puck in open sea: the screen turns the
  style's water colour (#31353f) instead of black. City streets never exist in the overview band.
- Name lookups (`queryRenderedFeatures`) only see layers of the *rendered* style: query per band
  and skip ids that are absent (the blank fallback style has none), never assume a layer exists.
- Protomaps `landuse` polygons have no `name` (only `kind`, `sort_rank`); park names are `pois`
  points. Decode a tile before designing a lookup: `sqlite3` + gzip + a 40-line MVT varint parser
  (no pip in the sandbox) shows the real keys per layer and zoom.
- A lookup that returns nothing on device with plausible code: add `Log.d` with the query box and
  feature counts and ship it (one CI round) instead of reasoning for an hour; the first fix after a
  camera jump projects off-screen, so expect nulls until the ease finishes.
- Unresolved reference in CI after a fixer/agent adds a call: check the import block first
  (`Geo` used in MapController without `import …geo.Geo`).
- Emulator fixes carry ±5 m accuracy, and derived speed only counts when the per-second displacement
  exceeds the accuracy: simulate ≥ 20 km/h (5.5 m/s) to trigger speed-based features; 6 km/h reads
  as standing still. Helper: `speed.sh <kmh> <seconds> [lat] [lon]` (one fix per second, eastwards).
- Auto theme on the emulator follows the *simulated* position (California) with the *host* clock
  (Paris): 23:00 local shows light because it is afternoon in California. Expected, not a bug.
- A ListPreference dialog / popup menu shifts when items are added: screenshot before tapping.
- Round-3 recipe worked first-try on CI again: research workflow (3 agents, verified facts to
  files) → brief with per-module file ownership and exact signatures → 5 implementers → 3 reviewers →
  apply the "minor" findings yourself (they were real behaviour bugs: toast-before-start, stale
  speed, dialog races) → push.
- A feature that needs a *file* (GPX import) is testable end to end: `adb push` it to
  `/sdcard/Download/`, broadcast `MEDIA_SCANNER_SCAN_FILE`, and the `OpenDocument` picker opens on
  Downloads with the file already visible — no navigation needed.
- Test a geometry feature with geometry you control: lay the route along the exact line
  `speed.sh` drives (constant latitude, moving east), then a second script that drifts perpendicular
  and *holds* position. Holding matters — an off-route rule with a delay only trips if fixes keep
  arriving from the same wrong place; a single jump proves nothing. Reading back "159 m away"
  against a simulated 160 m offset validates the projection maths, not just the alert.
- Verify a share/export path by opening the sheet and stopping there. The sheet listing the right
  file name proves the FileProvider grant; actually sending it would publish the user's data.

## Going public checklist (done 2026-09-08)

- Audit `git ls-files` for keys/fonts/secrets; a committed keystore is compromised the moment the
  repo is public: generate a new PKCS12 with openssl, store it base64 in GitHub secrets
  (`gh secret set` works only through the unsandboxed osascript shell), decode in CI to
  `$RUNNER_TEMP`, drop every default password from Gradle, delete old releases/tags, purge the
  blob from history (`git filter-branch --index-filter 'git rm --cached --ignore-unmatch …'
  --tag-name-filter cat -- --all`, then force-push). Keep a copy of the key for the user outside
  the repo (`~/Documents/VeloTrack signing key/`).
- Commercial fonts (Interstate, Helvetica…) cannot ship in a public repo or a free APK: swap to an
  OFL font from google/fonts (curl the TTFs from github.com/google/fonts/raw/main/ofl/<family>/).
- 3dicons.co icons are CC0; Material Symbols Apache-2.0; Protomaps basemaps BSD-3; OSM data ODbL:
  list everything in THIRD_PARTY.md and credit OSM in the README.
- Root README = project landing page (what, install link, layout, contributing, licence); app
  README = full user + build guide; `PATCH /repos/{owner}/{repo}` with `{"private": false}`.

## Google Play kit (2026-09-08)

- Play needs an `.aab`: add `bundleRelease` to the gradle step and publish
  `app/build/outputs/bundle/release/*.aab` next to the APK. Our CI key is the *upload* key; Play
  App Signing holds the store key.
- Answer Play's warnings from the artefact, not from memory: `unzip -l app.aab` shows whether
  `BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map` is there (AGP adds it whenever
  R8 is on, so the "deobfuscation" half is usually already solved). For the "native debug symbols"
  half, read the ELF section headers of the bundled `.so` (40 lines of `struct.unpack` beats
  installing binutils): if there is no `.symtab` and no `.debug_*`, the dependency shipped it
  stripped and `ndk.debugSymbolLevel` would only drag a whole NDK into CI to package nothing.
  MapLibre 13.6.0 is in exactly that state — leave the warning alone and write down why.
- Automate the upload with `r0adkll/upload-google-play@v1.1.5` (`serviceAccountJsonPlainText`,
  `releaseFiles` glob, `track: internal`, `whatsNewDirectory` holding a `whatsnew-en-GB` file under
  500 chars). Keep it dormant: the `secrets` context is unreadable from a step-level `if`, so have
  an earlier step write `have=true/false` to `$GITHUB_OUTPUT` and gate on that. The service-account
  JSON is a credential the account owner must create and paste as a secret themselves.
- Filter the build trigger so documentation cannot cut a release: `paths` accepts `!` exclusions
  after the positive pattern (`velotrack/**`, then `!velotrack/**.md`, `!velotrack/store/**`).
  Without this, editing a store markdown file bumps versionCode and publishes a GitHub release.
- Store assets without design tools: SVG → `qlmanage -t -s 1024 -o . file.svg` (unsandboxed via
  osascript; thumbnails come out square, so draw a 1024x1024 SVG and `sips --cropToHeightWidth`
  to 1024x500) and `sips --cropToHeightWidth 2160 1080` for phone screenshots (Play rejects
  aspect ratios above 2:1; the emulator's 1080x2424 is 2.24:1).
- Texts, privacy policy and the console checklist live in `velotrack/store/` and
  `velotrack/PRIVACY.md`. The console forms were driven end to end with Claude in Chrome
  (2026-09-08). Lessons: direct URLs work for `app-content/<page>`, `main-store-listing`,
  `store-settings`, `tracks/internal-testing`, `closed-testing`, `publishing`; the side-nav is
  collapsible and unreliable. `form_input` does not trigger the console's (Angular) validation
  and can silently fill hidden duplicate inputs — click + type + Tab for anything that must
  validate, and reload the page if a hidden field goes "invalid". Release notes must keep the
  `<en-GB> … </en-GB>` tags. Graphics: `Add assets` opens an asset library; upload through the
  hidden `input[type=file]` (find it after clicking, ref appears as a file button), then hover the
  asset row → arrow → **Add**. Bundles > 10 MB cannot go through the extension's file_upload:
  push the .aab to a temporary branch, `fetch()` it in-page from raw.githubusercontent.com
  (CORS `*`; loopback servers are blocked by Chrome's private-network rule), build a `File`, set
  `input.files` via `DataTransfer`, dispatch `change`, then delete the branch. The FGS
  declaration accepts a GitHub blob URL of an .mp4 as the demo video. New personal accounts: setup
  → internal test → closed test (all countries + tester list) → "Send 15 changes for review" only
  unlocks after the closed-test release is saved; production needs 12 testers for 14 days.

## Where things are

- Repo `~/Documents/GitHub/fablab-tools`, app `velotrack/`, specs/plans under `docs/superpowers/`,
  releases `velotrack-latest` (rolling) and `velotrack-v<run>`; test map `map-mountain-view-test`.
- Skill copies with templates/research: `fablab-tools/skills/build-1/`, `fablab-tools/skills/android-app-2/`.
- Emulator helpers: `skills/build-1/templates/emulator-ride.sh`; PMTiles dry-run `velotrack/tools/pmtiles_dryrun.py`.

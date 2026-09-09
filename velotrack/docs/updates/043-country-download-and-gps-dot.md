# Build 43 — download any country, Simple/Detailed, and a GPS status dot

Date: 9 September 2026. Commit: pending. Tag: `velotrack-v43`.

## What a rider notices

The Download map screen has a new **Country** section: **Choose a country** opens a searchable
list of all 195 UN member and observer states, and picking one shows two buttons — **Simple map**
(main roads, the same detail as the existing France preset) and **Fully detailed** (every street).
The name and the real download size update immediately either way.

The GPS line in the statistics card now has a small dot in front of it: green once the phone has a
fix, red while it is still searching or location is off — a glance a rider can read without parsing
"GPS 9/14 · ±5 m" over rough ground.

## Why

Both requested directly. The country list needed real data, not a token gesture — see below. The
detail toggle needed a genuine size ceiling, which is the part that turned into the interesting bug
of this build.

## What changed

| File | Change |
|---|---|
| `download/Countries.kt` | New. 195 countries, bounding box each, generated once from public data (not fetched at build or run time — see the file's own header for exactly which sources and why). |
| `ui/CountryPickerActivity.kt` + `layout/activity_country_picker.xml` | New. Search-as-you-type list, reusing the existing `ListRowAdapter`/`item_list_row.xml`. |
| `ui/DownloadMapActivity.kt` | Country picker launcher, Simple/Detailed toggle, `Area.BBox` gained an optional raw name (195 countries can't each get a string resource). Picking a country clears the Area/Preset chips and vice versa. |
| `download/PmTilesRemote.kt` | **The actual fix in this build** — see below. |
| `res/layout/activity_main.xml`, `layout-land/activity_main.xml`, `drawable/bg_status_dot.xml`, `values/colors.xml`, `values-night/colors.xml` | The GPS status dot: a plain tinted circle, colour set in code from the same condition that already chooses the GPS text. |

## The bug this exposed, and the fix

Testing "Germany, Fully detailed" crashed the `:download` process:

```
java.lang.OutOfMemoryError: Failed to allocate a 32 byte allocation with 240960 free bytes
	at com.fablab503.velotrack.pmtiles.PmDirectory.decode(PmDirectory.kt:68)
	at com.fablab503.velotrack.download.PmTilesRemote.leafDirectory(PmTilesRemote.kt:196)
```

Nothing before this build could ask for street-level detail (z13-15) across a country-sized area —
the closest anything came was "10 km around me, every street" (tiny area) or "1000 km, big roads
only" (coarse zoom). `PmTilesRemote.plan()` builds one `PlannedTile` and one `HashMap` entry per
grid cell before it does anything else with them; for all of Germany at z13-15 that is over
1.2 million cells, and building that many small objects exhausted the download process's heap
before a single byte was fetched. The leaf-directory cache the stack trace points at is a red
herring — it is already a bounded LRU (48 entries) precisely because PMTiles directories are
structured to keep the whole planet's leaf count near-constant regardless of zoom; the unbounded
part was the plan's own tile list.

The fix adds a cheap pre-flight count in `PmTilesRemote.plan()`: before touching the directory at
all, add up the grid cells `Mercator.tileRanges` would visit across the requested zoom range (pure
arithmetic, no lookups), and refuse anything over 300,000 cells with a clear message rather than
building the list and finding out. 300,000 was sized from the observed failure, not guessed: at
roughly 100-150 bytes per tile once `HashMap` boxing is counted, that ceiling keeps one band's plan
under ~45 MB, comfortably inside a normal heap even with several bands planned back to back (a new
country download plans every coarser band too, not just the one asked for). It corresponds to
roughly a 150,000-200,000 km² country at full street detail — a mid-sized country, not a small one.

## How it was checked

- New test `download/PmTilesRemoteSizingTest.kt`: Germany at z13-15 exceeds the limit (the exact
  crash case), a routine 10 km download and Monaco at full detail both stay far under it, and the
  formatted-count/exception-message helpers are checked directly. All pass.
- Full clean rebuild: zero warnings, `BUILD SUCCESSFUL`, all test classes green (the five new ones
  included).
- Emulator, end to end: opened Download map → Choose a country → searched "germany" → selected it
  → Simple map estimated 58 MB / 371 tiles → switched to Fully detailed → **"Could not calculate
  the size: This area needs about 1,209,543 tiles at this detail — too many to plan at once. Try
  Simple map, a smaller area, or a smaller country."** — no crash, Download stayed disabled.
  Immediately after, searched "monaco", picked it with Fully detailed still selected from before,
  and got a real estimate: 2.4 MB / 69 tiles, Download enabled.
- GPS dot: photographed both states on the emulator — green with a fix ("GPS 0/6 · ±5 m"), red
  on a fresh cold start before any fix ("GPS: searching…").

## Not checked / known risks

- Not tested on the Xiaomi (same local-build-vs-Play-signature limit as every build since 40).
- The 195-country dataset is mainland/home-territory extents, not every overseas territory —
  documented in `Countries.kt`'s own header. France there is metropolitan France, distinct from the
  pre-existing "All of France" preset chip.
- Russia, Fiji and Kiribati needed a manual antimeridian correction (their naive min/max longitude
  spans nearly the whole globe); documented inline in `Countries.kt` with the reasoning.

## Rolling back

`git revert <sha>`. Clean — no stored data changes shape; a rider who already downloaded a country
keeps it after a rollback, they just lose the picker to make a new one until the revert itself is
reverted.

# Build 42 — the download screen no longer asks "my position or map centre"

Date: 9 September 2026. Commit: pending. Tag: `velotrack-v42`.

## What a rider notices

Opening **Download map** used to start with a choice: "My position" or "Map centre". That row is
gone. The screen now goes straight from the title to the area choices (Nearby / Around here / Wide
area / Whole world / presets).

## Why

Asked directly: "take out map centre." The choice was confusing rather than useful in practice —
most riders download for where they are standing, and the distinction only mattered in the one
case where there is no GPS fix yet.

## What changed

| File | Change |
|---|---|
| `ui/DownloadMapActivity.kt` | Removed `useMapCentre` and the button-group wiring. `centre()` is now simply `fix ?: mapCentre` — always the rider's GPS position, silently falling back to wherever the map was showing only when there is no fix yet (indoors, cold start). The auto-generated name ("Around me 10 km" vs "Map centre 10 km") still reflects which one was actually used, so a download made without a fix is still labelled honestly. |
| `res/layout/activity_download_map.xml` | Removed the "Centre" label and the two-button toggle group; kept the "No position yet" message for the case where neither a fix nor a map centre is available at all. |
| `res/values/strings.xml` | Removed the three strings that only existed for the removed row (`download_centre_label`, `download_my_position`, `download_map_centre`). `download_name_map_centre`, used for the fallback-name case, is untouched. |

## How it was checked

- `gradle assembleDebug testDebugUnitTest`: `BUILD SUCCESSFUL in 24s`, zero warnings, all tests
  green.
- Installed on the emulator and opened **Download map** from the no-map card: the screen goes
  straight to **Area** with no gap where the toggle used to be, "Nearby · 10 km · every street" is
  pre-selected, and the name field reads "Around me 10 km" (the emulator has no GPS fix, so this is
  the honest fallback name, and it still worked without a crash).

## Not checked / known risks

- None. This removes a choice; it does not change what a download without a GPS fix actually
  downloads — that fallback existed before and is unchanged, just no longer offered as a button.

## Rolling back

`git revert <sha>`. Clean — restores the toggle and its three strings exactly.

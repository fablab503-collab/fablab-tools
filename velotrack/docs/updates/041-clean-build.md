# Build 41 — the build is warning-free

Date: 8 September 2026. Commit: pending. Tag: `velotrack-v41`.

## What a rider notices

Nothing. This is the three compiler warnings CI has been printing since build 34, gone.

## Why

Every build log carried the same three lines, harmless but a habit worth breaking before a real
bug hides in the noise:

```
w: AddFavoriteDialog.kt:90:45 Condition is always 'true'.
w: MainActivity.kt:278:14 'fun setPrefetchesTiles(p0: Boolean): Unit' is deprecated.
w: PlacePickerActivity.kt:79:14 'fun setPrefetchesTiles(p0: Boolean): Unit' is deprecated.
```

**The redundant condition:** `fixedKind` is defined as `existing != null && (existing.kind ==
HOME || existing.kind == WORK)`, so checking `existing != null` again beside it was dead weight —
Kotlin's compiler could prove `fixedKind` already implies it.

**The deprecated call:** `MapLibreMapOptions.setPrefetchesTiles(false)`, set at map-construction
time, is deprecated with no builder-time replacement. Checking the MapLibre 13.6.0 jar directly
(`javap -v`, not just trusting the warning text) showed the map instance's own
`setPrefetchesTiles(boolean)` is *also* deprecated — its bytecode carries the same
`Deprecated: true` flag — in favour of `setPrefetchZoomDelta(int)`, which is not deprecated. The
official API docs confirm the message: *"Use setPrefetchZoomDelta instead"*, default delta 4,
**0 disables prefetching outright** — which is what this app wants, since every tile comes from a
bundled offline file and there is nothing to usefully prefetch over no network.

## What changed

| File | Change |
|---|---|
| `ui/AddFavoriteDialog.kt` | `if (fixedKind && existing != null) existing.kind else …` → `existing?.kind?.takeIf { fixedKind } ?: when (…)`. Same behaviour, no redundant check, no `!!`. |
| `ui/MainActivity.kt` | `.setPrefetchesTiles(false)` moved off the options builder and replaced with `m.setPrefetchZoomDelta(0)` inside `getMapAsync`. |
| `ui/PlacePickerActivity.kt` | Same change. |

## How it was checked

- `gradle --no-daemon clean assembleDebug testDebugUnitTest` from a clean tree: **zero `w:` lines**,
  `BUILD SUCCESSFUL in 31s`, all 24 test classes reported, zero failures.
- Verified `setPrefetchZoomDelta`'s deprecation status directly from the compiled class file
  (`javap -v`) rather than trusting the compiler's one-line message, since the message named a
  method that turned out to be deprecated too — checking the bytecode found that before it became
  a second round of build warnings.

## Not checked / known risks

- `setPrefetchZoomDelta(0)` is a map-wide setting; if a future feature wants prefetching back for
  some other reason, it now needs an explicit non-zero delta rather than `true`.

## Rolling back

`git revert <sha>`. Clean — no behaviour changes, only which (undeprecated) API asks for the same
thing.

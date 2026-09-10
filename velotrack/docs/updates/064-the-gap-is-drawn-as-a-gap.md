# Build 64 — A lost signal looks like a lost signal

Date: 10 September 2026. Commit: `<sha>`. Tag: `velotrack-v64`.

## What a rider notices

When the app loses GPS for a stretch, the ride no longer jumps across the map in a solid line as if
it had been ridden. The two ends are joined by a **thin dashed line**, half transparent, drawn
underneath the ride itself — so it is obvious at a glance that the app was not watching there, and
equally obvious that the ride continues on the other side.

## Why

Reported with a screenshot from a real ride: the track followed the streets, then shot in a
straight line across four blocks to the rider's position on Avenue Lepic.

The recorder had done the right thing. `PointFilter` starts a new segment whenever more than 200 m
or six minutes passes between stored points, and `RideStats` refuses to count that gap as distance —
the 640 m on screen was the streets only.

**Build 62 fixed half of this and the changelog claimed the whole of it.** `setTrackHistory` became
segment-aware, so a ride re-drawn from the database breaks in the right places. The line drawn
*while riding* did not, because `RideState` carried no segment index: `MainActivity` simply appended
each new fix to one live polyline whenever the stored point count went up. Distance was right; the
picture was wrong.

## Why a dashed line rather than nothing

Breaking the line and leaving the gap empty would have been the smaller change, and it would have
been worse. A ride that stops in one street and reappears in another, with nothing in between,
looks like the app lost the data. A dashed connector says what actually happened: *you were here,
then there, and nothing was measured in between.* It is drawn thinner (3 dp against 5 dp), at half
opacity, in the ride's own colour, and added to the style **before** the solid lines so it can never
be painted over one of them.

## What changed

| File | Change |
|---|---|
| `core/…/model/Models.kt` | `RideState.segment` — the index the recorder is writing into |
| `core/…/recording/RecordingService.kt` | Publishes `filter.segment` on every state update |
| `core/…/geo/TrackSegments.kt` | `gapsBetween(runs, joinM)`: the connectors between consecutive runs |
| `core/…/geo/TrackSegmentsTest.kt` | 5 more tests, including "runs that all but touch are not a gap" |
| `app/…/ui/MainActivity.kt` | A rise in `state.segment` breaks the live line before the next point |
| `app/…/map/MapController.kt` | `startNewTrackSegment()`, a `track-gap` source and dashed layer, and gaps derived from the drawn runs rather than stored twice |

`breakBeforeNextPoint` exists because the recorder's word has to beat the map's distance guess: six
minutes standing still is a gap the recorder saw and geometry alone cannot.

## How it was checked

- `gapsBetween` compiled and **run** against 6 assertions (empty input, a single run, end-to-start
  ordering, near-touching runs, two gaps in one ride, an empty run in the middle): all pass.
- Every changed file parses against `android.jar` with no structural errors.
- CI: unit tests and a release build.

## Not checked

Nobody has seen a dashed gap on a screen. Unproven: that the dash reads clearly at riding zoom, that
half opacity is visible enough in bright sun, and — the case that started this — that a real signal
loss mid-ride now breaks the live line rather than crossing it.

The other half of the report is not fixed here because it is not the app's to fix: the gap itself.
Android's battery saver switches GPS off when the screen is off, and VeloTrack deliberately uses the
GPS provider alone, which reacquires more slowly than the fused provider. Those are settings and
physics.

## Rolling back

`git revert <sha>` restores build 62's behaviour, including its half-fixed live line. Nothing
persists: no preference, no schema change, no file.

# Build 62 — Continue an old ride

Date: 9 September 2026. Commit: `<sha>`. Tag: `velotrack-v62`.

## What a rider notices

In **Tracks**, the ⋮ menu on any ride now starts with **Continue this ride**. It says what will
happen — the ride grows instead of a new one starting, its distance and moving time carry on, and
the gap since you stopped is left as a break — and then puts you back on the map with that ride
recording.

Useful for a tour ridden over several days, a ride split by a train, or an evening leg you want
added to the morning's rather than filed as a second ride.

The entry is hidden while something is already being recorded, because nothing can be added to a
ride while another one is running.

## Why

Asked for directly: "add the possibility to continue a old ride."

The engine could already do it. `RecordingService` has taken a `resumeTrackId` since the crash
recovery was written: it rebuilds the statistics from the stored points and starts a fresh segment.
Nothing exposed it for a ride the rider had finished on purpose, so the only way in was a crash.

## What changed

| File | Change |
|---|---|
| `core/…/geo/TrackSegments.kt` | New. `segmentsOf()` splits stored points into the runs they were recorded as |
| `core/…/geo/TrackSegmentsTest.kt` | New. 6 tests, including "a segment index that comes back never re-joins the earlier run" |
| `core/…/storage/TrackRepository.kt` | `reopenTrack()`: state back to `recording`, `finished_at` cleared |
| `core/…/recording/RecordingService.kt` | Calls `reopenTrack` when it continues an existing track |
| `core/…/recording/RideController.kt` | `resumeUnfinished` → `continueTrack`; crash recovery and Continue are the same request |
| `app/…/ui/TracksActivity.kt` | The action, and a dialog that says what it does before writing anything. Actions are now label-to-action pairs, so hiding the first entry cannot shift what the others do |
| `app/…/ui/MainActivity.kt` | `EXTRA_CONTINUE_TRACK_ID`, consumed in `onResume` (a foreground service may only be started while the activity really is in front) |
| `app/…/map/MapController.kt` | The drawn ride is now one line per segment, not one line through all of them |

## The map was drawing rides that never happened

Continuing a ride from last week would have drawn a straight line from where that ride ended to
wherever the new leg starts — across a hundred kilometres of countryside, in the app's own ride
colour, as if it had been ridden.

This was not new. `PointFilter` has always split a track into segments when more than 200 m or six
minutes passes between stored points, and `RideStats` has always refused to count the gap as
distance. The map did not know: `setTrackHistory` took a flat `List<LatLon>` and pushed it through
one GeoJSON `LineString`. So any existing ride with a train, a café or a wrong turn in it has been
drawn with a line across the gap, while the distance beside it correctly excluded that gap.

`setTrackHistory` now takes one list per segment and emits one feature per segment. The live line
joins what is already drawn only when the new point is within 100 m of its end — comfortably under
the recorder's own 200 m threshold, so it can never join across something the recorder itself
called a gap, while a ride reopened mid-way still draws as one continuous line.

## Deliberately not done

**The ride keeps its original start time.** A ride begun on Monday and continued on Wednesday is
still a ride that started on Monday, and that is what the list shows. Only elapsed time is affected
by this, and elapsed time is not displayed on the phone at all (it is sent to the watch, which
shows moving time). Moving time, distance, climb and average speed all continue correctly.

**Nothing is merged.** Continue adds to one ride; it does not join two existing rides together.

**The watch cannot do it.** `WearMenuActivity` lists the watch's own past rides but has no Continue
entry. The engine underneath is the same, so it is a screen's worth of work, not a design problem.

## How it was checked

- `segmentsOf` compiled with the Kotlin compiler inside Android Studio and run against a harness
  carrying the same 6 assertions as `TrackSegmentsTest`: **all pass**. `Models.kt` compiled with it,
  so `TrackPoint`'s field order is confirmed too.
- Every edited file parsed against `android.jar`; the only two errors reported were an exhaustive
  `when` over an enum the classpath could not see and a suspend call inside a `launch` it could not
  resolve — both pre-existing code, untouched.

## Not checked / known risks

**Not built, not installed, not run.** Gradle cannot start in this session: the sandbox refuses the
local socket its file-lock handler binds (`java.net.SocketException: Operation not permitted`).
So none of the following has been seen happen:

- A ride actually continuing: the row going back to `recording`, points appending, totals carrying
  on, and `finishTrack` closing it again.
- The segment-aware line on a real multi-segment ride. This is the change most worth watching,
  because it touched drawing code that already worked; a mistake here shows up as a missing line
  rather than a crash. Open any old ride that has a gap in it and check the gap is not bridged and
  the rest of the ride is still drawn.
- The 100 m join rule at a mid-ride app reopen (should look like one line) versus a continued ride
  (should not join).
- `reopenTrack` under a crash mid-continuation — the case it exists for.

## Rolling back

`git revert <sha>` restores build 61. A track that was continued and is still recording would be
left with `state = recording` and `finished_at = NULL`; the older code's crash-recovery dialog
handles exactly that row, so it closes cleanly. Nothing else persists.

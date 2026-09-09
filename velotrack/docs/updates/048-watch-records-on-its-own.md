# Build 48 — the watch records on its own, and says whether the phone is there

Date: 9 September 2026. Commit: pending. Tag: `velotrack-v48`.

## What a rider notices

On the watch: a small dot at the top, green when the phone is reachable and red when it is not,
with a label beside it naming the device that is actually recording — **From phone** or **On watch**.

And the watch no longer needs the phone. Press Start with no phone in range and the watch records
the ride itself, with its own GPS, into its own database. It is a real ride, not a cache of one:
the same filtering, the same statistics and the same storage the handset uses, because that engine
is now shared code rather than something locked inside the phone app.

On the phone: nothing changes.

## Why

Asked for directly: a connection indicator, and a watch that "will work offline as independent"
when the phone is not there while still showing the phone's stats when it is.

## What changed

| File | Change |
|---|---|
| `core/` (new module) | The ride engine, moved out of `:app`: `model`, `geo`, `gpx`, `location`, `route`, `storage`, `settings/Prefs`, and the whole recording stack including `RecordingService`. 41 files changed path; none changed contents, because an import names a type and the Gradle module is not part of the name. |
| `core/.../recording/RecordingService.kt` | Two edits, the only ones in the move. Its `R` import became `…core.R` (this project sets `nonTransitiveRClass`, so each module's R holds only its own resources, and the notification icon and its 7 strings moved with the service). And it no longer names `ui.MainActivity`, which does not exist on a watch — it resolves the launcher activity through the package manager, the pattern `MapDownloadService` already shipped. |
| `wear/.../WearMainActivity.kt` | Two modes, switched automatically. Phone reachable and recording: mirror it, buttons send commands. No phone: record here through the same `RideController` and `RideSession` the phone drives. `useLocal()` is the single rule, and a ride started on the watch keeps the display even if a phone reconnects mid-ride. |
| `wear/src/main/AndroidManifest.xml` | Location, foreground-service and wake-lock permissions; `RecordingService` declared; `android.hardware.location.gps` **not** required, so a watch without GNSS still works as a display. |
| `app/src/main/res/values/wear.xml` (new) | The phone advertises a `velotrack_phone` capability. The watch looks for that rather than for any connected node, so a phone without VeloTrack installed reads as "no phone" instead of a phone that never answers. |
| `wear/src/main/res/layout/activity_wear_main.xml` | The connection dot and source label, and the stack made scrollable. |
| `wear/build.gradle.kts` | `versionCode` now offsets by 1,000,000. |

## The defect this fixes in build 47

`:app` and `:wear` both set `versionCode` to the CI run number, so the two artifacts carried
**identical** version codes. They share an application ID, and Play rejects that outright — the
first watch upload would have failed. Build 47 shipped that bug; it is fixed here before it could
be hit.

## How it was checked

- All 243 existing tests still run and pass, now split 122 in `:app` and 121 in `:core`, plus the
  8 watch tests. The move dropped nothing.
- **The phone's recording notification, on a real ride**, because that is the only behaviour the
  extraction changed. `dumpsys` shows `startForegroundCount=1`, `isForeground=true`, type `0x8`
  (location), the notification titled "Recording ride" — one of the strings that moved to `:core` —
  the moved icon resolving to a real resource id, a live `startActivity` content intent, and
  statistics ticking at "0.0 km 0:00:02". Crash buffer empty, no `NotFoundException`.
- `cmd package resolve-activity` confirms `getLaunchIntentForPackage` returns `ui.MainActivity`,
  which is exactly where the deleted code pointed.
- **Standalone recording on the watch**, driven by a scripted GPS track through `adb emu geo fix`:
  `RecordingService` runs on the watch with `isForeground=true` and type `0x8`, and the screen shows
  a red dot, "On watch", "Recording", **0.07 km** and a running 0:23 clock. The distance is the
  proof that matters — it means `PointFilter`, `RideStats` and the haversine sum all ran on the
  watch, not that a number was displayed.
- A layout defect was found and fixed in the same pass: adding the connection row pushed the buttons
  off a screen that is only about 192 dp tall once the round-screen inset is taken. The stack is now
  scrollable, and `uiautomator` confirms all eight controls report on-screen, START included.

## Not done / known risks

- **The phone-to-watch link is still unproven**, unchanged from build 47. Pairing two emulators
  needs a Google sign-in that is not available here, so "From phone" mode has still never carried a
  byte. Everything verified above is the *standalone* path, which needs no pairing — that is
  precisely why it was built first.
- Speed reads 0.0 in the test because `adb emu geo fix` sets position without a speed value and the
  points arrive as discrete jumps. Distance and elapsed time are real; speed was not exercised.
- No map on the watch yet, and no map download on the watch. Both are designed and sequenced but
  not built.
- Nothing here has run on watch hardware, and there is no watch to run it on. Battery cost of
  continuous GPS on a real watch is unmeasured.
- The watch's rides do not yet sync back to the phone, and the phone's tracks, settings and
  favourites are not yet on the watch.

## Rolling back

`git revert` of either commit. `:core` is a pure move, so reverting it restores the previous file
layout exactly; nothing persistent changed on any device.

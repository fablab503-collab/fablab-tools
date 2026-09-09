# Build 46 — the app installs on every device that can use it, not only ones with GPS

Date: 9 September 2026. Commit: `94d9304`. Tag: `velotrack-v46`.

## What a rider notices

On a phone, nothing at all. What changes is who can install the app: a Chromebook, a Wi-Fi-only
tablet or a headset can now get it from Google Play and use it to review past rides, import and
browse offline maps, and export GPX. Recording a ride still needs GPS and still says so on screen.

## Why

The manifest declared `android.hardware.location.gps` with `android:required="true"`. That is not
a hint — it is a hard filter in the Play catalogue, and the release page said so plainly:

| Form factor | Before | Newly supported | After |
|---|---|---|---|
| Phone | 11,228 | +194 | 11,422 |
| Tablet | 4,078 | +2,261 | 6,339 |
| TV | 1 | +3,002 | 3,003 |
| Wearable | 0 | 0 | 0 |
| Car | 25 | 0 | 25 |
| Chromebook | 0 | +72 | 72 |
| Android XR | 0 | +1 | 1 |
| **Total** | **15,332** | **+5,530** | **20,862** |

Every Chromebook and every headset in the catalogue was excluded outright, along with the large
share of tablets that ship without a GNSS chip. "Devices no longer supported" is 0 on every row:
this only adds.

Recording a ride genuinely does need GPS. Reviewing rides, managing offline maps and exporting GPX
do not, and those are the things a bigger screen is actually better at.

## What changed

| File | Change |
|---|---|
| `app/src/main/AndroidManifest.xml` | `android.hardware.location.gps` is now `required="false"`, and `android.hardware.touchscreen` is declared `required="false"` so Chromebooks and desktop-mode devices, which drive the app with a mouse or trackpad, are not filtered out either. |

Nothing else moved. `GpsSource` was already written for a device with no GPS: the `LocationManager`
lookup is `as?`, `isProviderEnabled` is wrapped against the `IllegalArgumentException` a missing
provider throws, `requestLocationUpdates` sits in a `try/catch` whose comment already reads
"Provider missing or security state changed underneath us", and the GNSS callback registration is
guarded the same way. The status dot added in build 43 reports the result to the rider. The
hardware flag was the only thing in the way.

## How it was checked

- Clean build, `BUILD SUCCESSFUL`, all unit tests green.
- `aapt2 dump badging` on the built APK now prints `uses-feature-not-required` for both
  `android.hardware.location.gps` and `android.hardware.touchscreen`, and
  `supports-screens: 'small' 'normal' 'large' 'xlarge'`. Reading the manifest source is not proof;
  the merged, packaged manifest is.
- **320 dp, the narrowest width Android supports** (emulator forced to 480x800 at 160 dpi, which is
  the `small` bucket). Portrait: the speed readout, the weather chip, the clock/battery/GPS row and
  all four statistics columns fit, with the button rail and the Record button clear. Landscape on
  the same screen keeps the panel-left layout rather than stretching the portrait one. Crash buffer
  empty in both orientations, and no VeloTrack exceptions in logcat.
- The device counts in the table above are read from the Play Console release page for build 46,
  not estimated.

## Not checked / known risks

- **Android TV went from 1 device to 3,003.** Declaring touchscreen optional is what unlocked it.
  The app has no `LEANBACK_LAUNCHER` intent filter, so it does not appear in the Android TV store's
  browsable listing and a TV owner would have to seek it out deliberately — but it is no longer
  blocked from installing, and a bike computer on a television is not a good experience. If TV
  installs ever show up in the statistics, add the leanback exclusion rather than reverting the
  touchscreen flag, which is what Chromebooks need.
- **Wearable is still 0, and this build does not change that.** A watch needs its own module and a
  watch-sized interface; it is a separate piece of work, not a manifest flag.
- Nobody has yet run the app on a real Chromebook or a real GPS-less tablet. The code path is
  exercised (the emulator has no GNSS chip either and reports "GPS: searching"), but a physical
  device on a large screen with a trackpad has not been tried.
- Not yet installed on the Xiaomi: build 46 changes nothing a rider can see there, and Play
  internal testing updates the phone on its own, as it did for build 45.

## Rolling back

`git revert 94d9304`. The flags go back to required and the catalogue narrows again at the next
release; nothing in the database or on disk is affected.

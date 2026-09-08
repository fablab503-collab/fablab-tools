# Build 36 — landscape becomes a real layout, and stops crashing

Date: 8 September 2026. Commit: `6cd4b27`. Tag: `velotrack-v36`.

## What a rider notices

Sideways, the statistics move into a 340 dp panel down the left with the buttons in a column down
the right edge, instead of a portrait layout stretched across a short wide screen where the button
column covered the speed. Guidance survives the rotation. The band of smeared streaks across the
top of the map is gone, and so is the crash.

## Why

Reported from a real ride: *"all the time that the screen went on landscape, it was crashing and
going out saying there was an error."*

One root cause explained all of it. `MainActivity` declared
`configChanges="orientation|screenSize"`, which tells Android not to recreate the activity on
rotation. That is usually a performance choice; here it meant `res/layout-land/` was **never read**,
so the landscape layout that existed was dead code, and MapLibre's GL surface was never told its
new size — hence the smeared band across the top, which was the renderer drawing a portrait-shaped
frame into a landscape window. The crash came from the same path.

The evidence that closed it: the phone's own recovery dialog after the ride said *"Ride 2026-09-08
15:36 (1,69 km) was still recording when the app was closed"* — the crash happened on the ride, not
in a menu.

## What changed

| File | Change |
|---|---|
| `AndroidManifest.xml` | `configChanges` reduced to `keyboardHidden` on `MainActivity` and `PlacePickerActivity`, so rotation recreates the activity and `layout-land` is actually used. |
| `res/layout-land/activity_main.xml` | New: 340 dp statistics panel on the left, 48 dp buttons down the right edge. |
| `res/layout-land/activity_place_picker.xml` | New: 320 dp card top-left so the crosshair keeps the true centre of the screen, which is what Save reads. |
| `map/MapController.kt` | `cameraPaddingLeftPx`, so the follow camera offsets the puck away from the covered side. |
| `ui/MainActivity.kt` | Guidance target saved in `onSaveInstanceState` and restored, since the activity is now recreated; insets applied on all four sides. |
| `res/values-land/dimens.xml` | Speed drops to 56sp — the screen is short. |

Portrait and landscape layouts must keep **identical id sets**, or ViewBinding makes the fields
nullable and the Kotlin stops compiling. A small checker script was used to diff the id sets.

## How it was checked

- Emulator (`Pixel_10a`): twelve rotations during an active recording, checking the pid before and
  after each — the process id never changed, so the app was surviving rotation rather than being
  quietly relaunched. A bare "is it running?" check would have missed exactly that.
- The pre-fix behaviour was photographed on the Xiaomi (then on build 26) to confirm the report:
  buttons over the speed, smeared band across the top.

## Not checked / known risks

- **MIUI refuses `settings put system` from adb** (`WRITE_SETTINGS` SecurityException), so rotation
  on the phone has to be driven with `cmd window user-rotation lock 0|1` instead.
- Whether the 55° tilt is still right in landscape, on a short wide viewport, was never settled.
  The horizon is closer to the top edge there than in portrait.

## Rolling back

`git revert 6cd4b27` also reverts the `configChanges` change, which is what actually fixed the
crash — do not revert this one to chase an unrelated landscape complaint.

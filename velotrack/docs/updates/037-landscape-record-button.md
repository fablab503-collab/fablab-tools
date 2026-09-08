# Build 37 — Record clear of the puck, hold opens the picker on the existing pin

Date: 8 September 2026. Commit: `8835737`. Tag: `velotrack-v37`.

## What a rider notices

Turning the phone sideways used to put the Record button exactly where the follow camera parks the
location puck, so the thing showing where you are sat underneath the thing you press. Record now
sits at the bottom-right of the map area, clear of it. And holding **Home** or **Work** when one is
already set opens the map picker centred on the existing pin, instead of offering "set from map
centre" and losing where the place actually was.

## What changed

| File | Change |
|---|---|
| `res/layout-land/activity_main.xml` | The record group moves to `bottom\|end` with `layout_marginEnd="@dimen/fab_standard_size"`. |
| `ui/MainActivity.kt` | The long-press menu passes the existing favourite's coordinates into `PlacePickerActivity`, so it opens on the pin rather than on the map centre. |

## How it was checked

- Emulator (`Pixel_10a`): twelve rotations mid-ride with the recording running; the record group
  never overlapped the puck and the app survived every rotation.

## Not checked / known risks

- Landscape was still reported as wrong after this build. That report turned out to be build 26
  behaviour on the phone — see [036-landscape.md](036-landscape.md) — but it is worth remembering
  that "landscape is broken" was said about a build that already had the fix, which is exactly the
  confusion this log exists to prevent.

## Rolling back

`git revert 8835737`. Clean.

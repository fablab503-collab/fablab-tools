# Build 63 — Choose the right address, then save it

Date: 9 September 2026. Commit: `<sha>`. Tag: `velotrack-v63`.

## What a rider notices

Searching an address in **Set Home** or **Set Work** now lists every match it found, as radio
buttons. Tap the right one; it ticks. The dialog's button changes to **Save this address**, and
nothing is written until that is pressed — so the name and the icon can still be set afterwards,
and a wrong first guess can be corrected by tapping a different row.

## Why

Reported twice. The second time: *"when i look for a place is finding me the place but there is no
way to clik or select it so it doesnt work. once he found the right adress it should be able to do
it."*

Build 60 had already made the result tappable, and reading that code found no defect in it — the
row was 48 dp tall, had a ripple, was clickable, and had a listener. It is likely the phone was
still on an older build. But the report was right anyway, because the design was wrong in two ways
that no amount of "the tap works" would fix:

- **It showed one result.** Nominatim returns up to five; the code took `first()` and discarded the
  rest. "Once he found the right address" describes choosing, and there was nothing to choose from.
- **A tap saved and closed the dialog.** That took the name field and the emoji picker with it. A
  rider who tapped the answer lost the two things the dialog was mostly made of.

So the fix is not a better tap target. It is to separate *which* from *whether*.

## What changed

| File | Change |
|---|---|
| `res/layout/dialog_place_picker.xml` | The single result `TextView` becomes an `addressStatus` line (searching / no match / failed) plus an `addressResults` `RadioGroup` |
| `ui/MainActivity.kt` | `showResults()` builds one radio per match; tapping only records the choice and relabels the button. `foundName` was dead state and is gone |
| `res/values/strings.xml` | `fav_use_address` "Use address" → "Save this address"; `fav_address_found` deleted (the radio shows the name itself) |
| `res/values/dimens.xml` | `touch_target_min` (48 dp), so a finger target is a named value rather than a number |

A `RadioGroup` rather than a hand-rolled list because "pick exactly one of these" is what it is
for: the ticked state is unmistakable, only one can be chosen, and it is announced correctly to a
screen reader without any extra work.

A single match ticks itself. One result is not a choice, and making the rider tap the only answer
before pressing the button is a step that exists for the code's benefit rather than theirs.

`PlacePickerActivity`'s own search was left alone. It moves the map to the top hit so the rider can
drag to fine-tune, which is a different and working interaction.

## Three fixes found while re-reading builds 61 and 62

Included here because they were made in the same pass:

1. **A zombie service.** Tapping *Keep recording* on a stale inactivity notification — a shade that
   had not refreshed, or a ride that ended a second earlier — created the service purely to answer
   the tap. It published `serviceRunning = true`, never called `startForeground`, and left the app
   convinced a ride was running that did not exist. It now checks a ride is actually in progress,
   and otherwise clears the notification and stops.
2. **A duplicated coordinate.** The live line is seeded from the last point of the drawn history, so
   at every 500-point flush that point was written into history a second time.
3. A stale KDoc left stacked above `setTrackHistory`, documenting a parameter that no longer exists.

## How it was checked

Static only, and thoroughly, because nothing can be built here (see below):

- Every `R.string` / `R.drawable` / `R.dimen` / `R.array` reference in the whole `:app` module and
  in `:core` resolves to a definition. Zero unresolved.
- `layout/activity_main.xml` and `layout-land/activity_main.xml` carry **identical id sets**, so
  ViewBinding cannot turn a field nullable and break the build.
- Every id used through `DialogPlacePickerBinding` exists in the layout; no reference to the removed
  `addressResult` survives anywhere.
- All 13 new cross-module symbols exist; no caller of the renamed `RideController.resumeUnfinished`
  or the removed `historyPoints` field remains.
- `InactivityReminder` 11/11 and `segmentsOf` 6/6 assertions pass, compiled and run with the Kotlin
  compiler inside Android Studio.
- Every changed Kotlin file parses against `android.jar` with no structural errors.

## Not checked

**Nothing here has been built, installed or run.** Gradle cannot start in this environment — the
sandbox refuses the local socket its file-lock handler binds — and `adb` cannot start its server for
the same reason. So the radio list has never been seen on a screen. Specifically unproven:

- That the app compiles.
- That the rows are legible and comfortably tappable with a long address on a narrow screen, and
  that the dialog is still reachable with the keyboard up.
- That the button relabels when a row is ticked.
- That saving stores the chosen address rather than the rider's position.

## Rolling back

`git revert <sha>` restores build 62. Nothing persists: no preference, no database column, no file.

# Build 61 — Energy saver, and a ride that reminds you it is still running

Date: 9 September 2026. Commit: `<sha>`. Tag: `velotrack-v62`.

## What a rider notices

The battery percentage in the statistics card is now a switch. Tap it and energy saver turns on:
the chip fills in and grows a bolt, the screen dims, the map goes flat and draws at half the frame
rate, and GPS is asked for a fix every two seconds instead of every second. Tap it again and
everything goes back. Hold it and a dialog explains what it actually changes and — more usefully —
the three things that save more than it does, which the app cannot do for the rider.

Separately: a ride that has not moved for ten minutes now raises a notification asking whether it
is over, with **Finish ride** and **Keep recording**. The ten minutes is a setting; it can be five,
fifteen, thirty, an hour, or never.

Both are in Settings under a new **Battery** section, and the switch there and the chip on the map
are the same setting.

## Why

Asked directly: "now is the time for energy saver, what's the best mode to save energy?" The honest
answer was that the screen dominates everything else and the app had no single switch for it — the
rider had to know to set *Screen during a ride* to dimmed, pick the dark theme, and use Follow 2D,
three separate settings in two places.

The second half came from the same conversation: a ride left recording after the rider has gone
inside quietly eats the battery it was meant to be saving, and produces a track with an hour of
nothing on the end.

The reminder deliberately does not stop anything by itself. A ride ended automatically at the top
of a col because the rider stopped to eat is data destroyed; a ride that ran two hours too long is
a track that can be trimmed. So it asks.

## What changed

| File | Change |
|---|---|
| `core/…/recording/InactivityReminder.kt` | New. Pure Kotlin, decides when to ask. Asks once per stretch of stillness, re-arms on movement, times a never-moved ride from its first still sample |
| `core/…/recording/InactivityReminderTest.kt` | New. 11 tests; the ones that matter pin "asked exactly once" and "keep recording restarts the clock rather than silencing it" |
| `core/…/recording/RecordingService.kt` | Feeds the reminder from fixes and from a re-armed handler (so it still fires when no fixes arrive at all); a second notification channel at `IMPORTANCE_DEFAULT` with Finish ride / Keep recording; `ACTION_KEEP_RECORDING`; re-registers GPS when energy saver changes mid-ride |
| `core/…/location/GpsSource.kt` | `start()` takes a fix interval and re-registers at a new one without dropping the GNSS status callback, so the satellite count does not blink |
| `core/…/settings/Prefs.kt` | `energySaver`, `inactivityReminderMin` |
| `app/…/ui/MainActivity.kt` | The chip: tap toggles, hold explains; energy saver dims a screen that would have been held at full brightness; applies to the idle map's own GPS client too |
| `app/…/map/MapController.kt` | `energySaver`: follow-mode cap 30 → 15 fps and tilt forced to 0, without touching the rider's chosen follow mode |
| `app/…/layout*/activity_main.xml` | The battery reading is wrapped in a tappable `energyChip` in both orientations |
| `app/…/xml/preferences.xml` | New **Battery** category |

## What energy saver is actually worth

Stated in the dialog too, because a saving that cannot be checked is a claim:

| Change | Size of the saving | Certainty |
|---|---|---|
| Screen dimmed instead of full brightness | Large | Certain — the screen is the biggest single consumer on a phone outdoors |
| Map 15 fps and flat instead of 30 fps and tilted | Moderate | Certain — fewer frames and less geometry per frame |
| GPS every 2 s instead of every 1 s | Small | Uncertain — whether the receiver idles between fixes is the chipset's decision, not the app's |

Two seconds rather than three: at 25 km/h that is about 14 m between fixes, comfortably inside
`PointFilter`'s 5 m thinning distance, so the shape of a corner survives. Coarser than that starts
cutting corners off the recorded line, which is a worse trade than the battery is worth.

## How it was checked

- `InactivityReminder` compiled with the Kotlin compiler shipped inside Android Studio and run
  against a harness carrying the same 11 assertions as `InactivityReminderTest`: **all pass**.
- Every edited Kotlin file parsed by the same compiler against `android.jar`; no syntax errors.
  Everything it did report was an unresolved reference to a dependency not on that classpath
  (MapLibre, AndroidX, Material), which is expected for a check run this way.

## Not checked / known risks

**This build has not been assembled, installed or run.** Gradle could not start in this session —
the sandbox refuses the local socket its file-lock handler binds
(`java.net.SocketException: Operation not permitted`), so `tools/ship.sh` fails before compiling.
Everything below is therefore unverified on a device:

- That the app compiles at all. The Kotlin parses and the resource names line up by inspection,
  but no `assembleDebug` has run and no `:core:testDebugUnitTest` has been executed.
- The chip's appearance in both orientations, and whether the pill reads clearly in sunlight.
- The reminder notification actually arriving after the timeout, and its two actions.
- Whether the dimmed screen, 15 fps cap and 2 s GPS produce a *measurable* battery saving on a real
  ride. Nothing here has been measured; the table above is reasoned from where the power goes, not
  from a discharge curve. A real check is two rides of the same route, same brightness, saver on and
  off, reading `dumpsys batterystats`.
- `POST_NOTIFICATIONS` is checked before the reminder is posted, so on a phone that denied it the
  reminder is silently skipped. The rider is not told this.

## Rolling back

`git revert <sha>` restores build 60. Two preferences (`energy_saver`, `inactivity_reminder_min`)
are left behind in SharedPreferences; they are ignored by the older code and harmless. The second
notification channel (`velotrack.reminder`) is created on the device and outlives a revert — Android
keeps channels until the app is uninstalled. Neither blocks a rollback.

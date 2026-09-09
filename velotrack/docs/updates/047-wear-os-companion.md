# Build 47 — a Wear OS companion: the ride on your wrist

Date: 9 September 2026. Commit: pending. Tag: `velotrack-v47`.

## What a rider notices

Nothing yet on the phone, and nothing in the Play Store: this build adds the watch app but does
not distribute it (see **Not done** below). What now exists is a Wear OS app that shows the ride
the phone is recording — speed, distance and elapsed time — with Start, Pause, Resume and Finish
buttons that drive the phone's recorder from the wrist.

## Why

Asked for directly, after build 46 left Wear OS as the one form factor still at zero devices. Build
46's changelog said plainly that a manifest flag could never fix that, because a watch needs its own
module and a watch-sized interface. This is that module.

The choice of a **companion** rather than a standalone recorder was the rider's, and it shapes
everything: the watch never touches GPS, never keeps a database, and never computes a statistic.
Every number on the wrist comes from the phone. That is the point — two independent copies of the
distance calculation would eventually disagree, and a bike computer that argues with itself about
how far you have gone is worse than one that shows nothing. It also means a watch with no GNSS chip
works exactly as well as one with it.

## What changed

| File | Change |
|---|---|
| `settings.gradle.kts`, `build.gradle.kts` | Two new modules, `:sync` and `:wear`. The library plugin is declared at the root: naming its version in the module fails, because AGP is already on the classpath from the application plugin and Gradle cannot check compatibility against an entry of unknown version. |
| `sync/` (new) | The wire contract, and nothing else. Both modules depend on it so a key can never be renamed on one side only — the Data Layer matches keys as plain strings, and a mismatch is silent: no exception, no log, the watch simply shows nothing. |
| `wear/` (new) | The watch app: one activity, a `BoxInsetLayout` that keeps content inside the square that fits within a round screen, and its own small number formatter. |
| `app/.../wear/WearPublisher.kt` (new) | Observes `RideSession` and mirrors it to the watch at 1 Hz. It observes rather than living inside `RecordingService`, so the recording path is untouched and does not know a watch exists. |
| `app/.../wear/WearCommandService.kt` (new) | A `WearableListenerService`. The system starts it when a button is tapped on the wrist, so a ride can be started while the phone is asleep and the app is not running. It calls `RideController`, the phone UI's own entry point, so a ride begun from the watch is identical in every way to one begun on the handset. |
| `app/.../VeloTrackApp.kt` | Starts the publisher — but only in the main process. |

Two channels, because state and events want opposite things. Ride state goes phone to watch as a
**DataItem**, which the Data Layer stores, so a watch that was asleep or out of range is handed the
current ride the moment it reconnects. Button taps come back as **Messages**, which are not stored,
because replaying a stale "stop" on reconnect would end a ride the rider is still on.

## Traps found on the way

- **The two-process bug that would have blanked the watch mid-ride.** This app already runs a
  second process (`:download`), and `VeloTrackApp.onCreate` runs in *both*. `RideSession` is a
  per-process object, so the download process holds its own permanently-IDLE copy. Publishing from
  there would have raced the real one and wiped the ride off the watch. The publisher is now guarded
  by a process check, which reads `/proc/self/cmdline` rather than `Application.getProcessName()` —
  that method needs API 28 and this app supports 26.
- **A payload the Data Layer refuses to deliver.** It drops a DataItem whose bytes are identical to
  the one already stored. Without a value that always changes, a rider stopped at traffic lights
  would watch the clock freeze. Hence `KEY_UPDATED_AT`.
- The Data Layer only delivers between apps sharing **both** an application ID and a signing
  certificate. The watch module therefore carries the same `applicationId` and the same `.debug`
  suffix as the phone, so a debug watch pairs with a debug phone and the pairs never cross.

## How it was checked

- Wear OS 5 emulator, 384x384 round (`ro.build.characteristics=watch`), created for this build
  after installing Google's command-line tools, which were absent from this Mac.
- The watch app installs, launches and renders, with an empty crash buffer. With no phone in range
  it logs `no connected phone for 'sync'` and shows "No phone" rather than a convincing row of
  zeros — the disconnected state is a real screen, not an oversight.
- Two layout defects were found by screenshot and fixed: the status line was clipped mid-sentence
  ("Open VeloTrack on your…") and the START label was cut off at the bottom by the DeviceDefault
  button's vertical insets. Both confirmed fixed in a second screenshot.
- Eight unit tests on the watch's formatter, named in the build log: metric and imperial speed and
  distance, the minute and hour clock shapes, and NaN, negative and sub-zero inputs, which is where
  a live speed feed actually breaks.
- **Regression check on the phone, which matters most here**, because `VeloTrackApp` runs in every
  process and a mistake there crashes the shipping app on launch: the phone build installs, starts
  and reaches its permission dialog with an empty crash buffer. All existing tests still pass.

## Not done / known risks

- **The live link is unproven.** Phone-to-watch delivery was never exercised, because pairing two
  emulators needs the Wear OS companion app and a Google account sign-in on the phone emulator,
  which is not something to do with the owner's credentials. Setting up the documented
  `adb forward tcp:5601` alone was not enough: the watch still reported no connected node. So the
  encode, transport and decode path is written and unit-tested at the edges but has never carried a
  real ride. **Treat it as unverified until a real watch is paired.**
- **It is not distributed.** CI builds the module but publishes only the phone bundle; the watch app
  reaches no one yet. Delivering it means adding the watch bundle to the same Play release as a
  second form-factor artifact, which is a separate piece of work.
- Never run on real watch hardware, and there is no watch to run it on.
- The watch shows no map, deliberately. A MapLibre surface on a 1.4-inch screen would be slow,
  unreadable, and hard on a battery measured in hours.

## Rolling back

`git revert <sha>`. The two new modules are additive; the only edit to shipping phone code is the
publisher start in `VeloTrackApp`, and the manifest entry for the listener service.

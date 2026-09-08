# Build 39 — touch feedback, Home answers the tap, no-map card centred

Date: 8 September 2026. Commit: `dc0adff`. Tag: `velotrack-v39`.

## What a rider notices

Every control now answers the finger. Tapping any of the buttons down the left edge, or anything
in the top bar, gives a short tick; holding one gives the longer buzz Android uses for a
long-press, so it is possible to tell a tap from a hold without looking down. Tapping **Home**
(or Work, or a favourite) now says where it is — the statistics card unfolds if it was folded away,
the dashed line to the place appears on the map, and a message names the distance and direction,
for example *Home · 392 m · W*. Holding the same button opens **Set from my position / Choose on
map / Clear**. And when there is no map downloaded yet, the *No map data yet* card sits full width
directly under the statistics instead of floating in the middle of the screen with the button
column overlapping it.

## Why

Three separate reports, all from riding rather than from reading the code.

**"Give me a little feedback, like a little vibration when I click on the icons."** There was none:
the buttons are flat by design (build 34 removed the drop shadows because on the night style they
read as a grey smudge rather than as depth), which looks right but leaves nothing to confirm a
press on a bar over rough ground.

**"The Home icon is not working properly. Sometimes I click on it and nothing happens."** It was
working every time. Guidance is drawn *inside* the statistics card, and build 34 made that card
foldable. A rider who had folded it away to see more map lost the only place the result could
appear — the app started guidance and said so into a panel that was not on screen. This is the
kind of bug that never reproduces for the person who wrote it, because they never fold the card.

**"The layout is not perfect — the map is in the centre and the icons on the left."** With no map
downloaded, the *No map data yet* card was part of the map overlay, so the button column sat on top
of it.

## What changed

| File | Change |
|---|---|
| `ui/MainActivity.kt` | Two view extensions, `onTapWithFeedback` (`HapticFeedbackConstants.CONTEXT_CLICK`) and `onHoldWithFeedback` (`LONG_PRESS`), now wrap all 13 controls: record, pause, stop, recentre, 3D, menu, download map, the statistics panel, show-HUD, Home, Work, favourites and stop-guidance. |
| `ui/MainActivity.kt` | `onFixedFavoriteClicked` unfolds the statistics card before starting guidance and shows the distance and direction as a message, so the tap is answered even before the first GPS fix arrives. |
| `res/layout/activity_main.xml` | The no-map card moves out of the map overlay into `topPanel`, below the GPS banner: `match_parent` wide, 72 dp illustration beside the text. |

`performHapticFeedback` deliberately obeys the system touch-feedback setting rather than
overriding it with `FLAG_IGNORE_GLOBAL_SETTING`. A rider who has turned haptics off has turned them
off. The consequence worth remembering: on MIUI, with haptics turned down, this build feels
identical to build 38 and the change is not broken.

## How it was checked

- **Emulator (`Pixel_10a`, Android 16)** — the haptic was proved from the system's own record, not
  from the code. `adb shell dumpsys vibrator_manager` before and after three taps differs by:

  ```
  effect | finished | duration: 130ms | usage: TOUCH | com.fablab503.velotrack (uid=10234)
  reason: performHapticFeedback(constant=6): ViewRootImpl#performHapticFeedback
  played: Prebaked=TICK(MEDIUM, with fallback)
  ```

  `constant=6` is `CONTEXT_CLICK`. This dumpsys diff is the only honest way to prove a haptic
  fired; a screenshot cannot show it.
- **Emulator** — tapping Home with a Home favourite set produced the guidance row *→ Home · 392 m ·
  W* with a Stop button, and the dashed guidance line from the puck. Holding Home produced the
  **Home / Set from my position / Choose on map / Clear** sheet. Both photographed.
- **Emulator** — after `pm clear`, the *No map data yet* card renders directly beneath the
  statistics card, full width, with the button column starting well below it.
- Crash buffer clean on install and through the whole sequence.
- Published to the Play internal testing track as 39 (1.0.39) at 19:54.

## Not checked / known risks

- **Not tested on the Xiaomi.** The phone is on build 38 from Play, and a locally built APK cannot
  replace a Play-signed install. The haptic is the part that most deserves a real device, because
  the emulator has no motor: the log proves the call reached the vibrator service, not that a
  finger feels it.
- The message uses a toast, which on some MIUI configurations is suppressed for background-ish
  apps. The guidance row inside the card is the reliable half; the toast is the extra.

## Rolling back

`git revert dc0adff` restores build 38 behaviour. Nothing here migrates data or changes the meaning
of a stored preference, so the revert is clean.

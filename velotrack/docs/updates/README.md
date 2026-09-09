# Update log

One page per build, written when the build is made. The point is not ceremony: when a rider says
"the Home button stopped working again", the fastest way back to the cause is a page that says
what changed in that build, why, and how it was checked. A commit message says what was edited; a
page here says what a rider would notice and what was actually proven on a device.

The build number is the GitHub Actions run number, which is also the `versionCode` in the Play
Console and the `velotrack-v<n>` tag in this repo — one number, the same everywhere. Documentation
commits do not consume one: since build 31 the workflow ignores changes under `velotrack/**.md`,
`velotrack/store/**` and `velotrack/docs/**`, so writing these pages is free.

Pages are named `<build>-<slug>.md`, zero-padded to three digits so they sort. Start from
[`TEMPLATE.md`](TEMPLATE.md).

## Builds

| Build | Date | What a rider would notice | Page |
|---|---|---|---|
| 63 | 9 Sep 2026 | Set Home / Set Work lists every address it found and lets you tick the right one; nothing saves until you press the button | [063-choose-the-right-address.md](063-choose-the-right-address.md) |
| 62 | 9 Sep 2026 | Continue this ride, in the Tracks menu: a ride grows instead of a new one starting. And the map stops drawing a straight line across every gap in a ride | [062-continue-an-old-ride.md](062-continue-an-old-ride.md) |
| 61 | 9 Sep 2026 | The battery percentage is a switch: energy saver dims the screen, flattens the map and halves the GPS rate; hold it for what actually saves a battery. A ride that has not moved for ten minutes asks whether it is over | [061-energy-saver-and-forgotten-rides.md](061-energy-saver-and-forgotten-rides.md) |
| 60 | 9 Sep 2026 | The address found by Set Home / Set Work can be tapped to use it, and the dialog button says "Use address" | — |
| 59 | 9 Sep 2026 | A leaf chip on the map: what a car would have emitted over the distance you have ridden, with the sources and an honest account of what it means | [059-co2-comparison.md](059-co2-comparison.md) |
| 48 | 9 Sep 2026 | The watch records rides on its own with no phone, and a green/red dot says whether the phone is there | [048-watch-records-on-its-own.md](048-watch-records-on-its-own.md) |
| 47 | 9 Sep 2026 | A Wear OS app showing the phone's ride on the wrist, with Start/Pause/Finish. Built and rendering, but not yet distributed and the live link is unproven | [047-wear-os-companion.md](047-wear-os-companion.md) |
| 46 | 9 Sep 2026 | Nothing on a phone. The app can now be installed on Chromebooks, GPS-less tablets and headsets: 15,332 supported devices become 20,862 | [046-every-device-that-can-use-the-app.md](046-every-device-that-can-use-the-app.md) |
| 45 | 9 Sep 2026 | Every saved place shows on the map; pick an emoji for its icon | [045-favorite-markers-and-emoji-icons.md](045-favorite-markers-and-emoji-icons.md) |
| 44 | 9 Sep 2026 | Search an address for Home/Work/favourites; a weather chip by the clock | [044-address-search-and-weather.md](044-address-search-and-weather.md) |
| 43 | 9 Sep 2026 | Download any of 195 countries, Simple/Detailed; a green/red GPS status dot | [043-country-download-and-gps-dot.md](043-country-download-and-gps-dot.md) |
| 42 | 9 Sep 2026 | Download map no longer asks "my position or map centre" — it just uses your position | [042-remove-map-centre-download-choice.md](042-remove-map-centre-download-choice.md) |
| 41 | 8 Sep 2026 | Nothing. The build is warning-free for the first time | [041-clean-build.md](041-clean-build.md) |
| 40 | 8 Sep 2026 | Nothing. Builds now happen on this Mac in 8 seconds and install straight onto a phone; this log begins | [040-build-and-install-from-this-mac.md](040-build-and-install-from-this-mac.md) |
| 39 | 8 Sep 2026 | Buttons vibrate under the finger; Home answers a tap with the distance; the no-map card sits under the statistics | [039-touch-feedback.md](039-touch-feedback.md) |
| 38 | 8 Sep 2026 | The place picker names the street straight away instead of after the first drag | [038-place-picker-names-the-street.md](038-place-picker-names-the-street.md) |
| 37 | 8 Sep 2026 | In landscape the Record button no longer sits under the location puck; holding Home opens the picker on the existing pin | [037-landscape-record-button.md](037-landscape-record-button.md) |
| 36 | 8 Sep 2026 | Landscape is a real layout instead of a stretched portrait one — and it stops crashing on rotation | [036-landscape.md](036-landscape.md) |
| 35 | 8 Sep 2026 | Nothing visible. Every push now uploads to the Play testing tracks by itself | — |
| 34 | 8 Sep 2026 | Flat controls, a statistics card that folds away, a map picker for Home/Work/favourites, plain-language download presets | — |
| 33 | 8 Sep 2026 | Nothing visible. CI prints each test result so a test that stops being discovered cannot look like a pass | — |
| 32 | 8 Sep 2026 | Nothing visible. A test replays a 180,000-point ride (1000 km) through simplification, statistics and GPX export | — |
| 31 | 8 Sep 2026 | Nothing visible. Documentation commits stop burning a version code; the Play upload step is added, dormant | — |
| 30 | 8 Sep 2026 | Nothing visible. Records that Google approved the first Play submission | — |
| 29 | 8 Sep 2026 | Nothing visible. Play Console status notes | — |
| 28 | 8 Sep 2026 | Nothing visible. Adds the screen recording Play requires for the foreground-service declaration | — |

Builds 1–27 predate this log. They are recoverable from the `velotrack-v<n>` tags:
`git ls-remote --tags origin 'refs/tags/velotrack-v*'` gives the authoritative build-to-commit
mapping, and `git show --stat <sha>` the change. Tags exist for v21 and v23–v39; build 22 consumed
a run number without producing a commit, so there is nothing behind it.

## How a build gets made and checked

The Mac now has a full Android SDK and the JDK that ships inside Android Studio, so a build no
longer has to go through GitHub Actions to be testable. [`../../tools/ship.sh`](../../tools/ship.sh)
does the whole loop:

```
tools/ship.sh                 build, install on every attached device, launch, screenshot, read the
                              crash buffer, and fail loudly if any of that did not happen
tools/ship.sh --build-only    build only
tools/ship.sh --play FILE     skip the build and install a Play-signed APK instead
```

It builds the **debug** variant. That matters: `app/build.gradle.kts` gives the debug build type an
`applicationIdSuffix` of `.debug`, so it installs as a second app next to the Play copy — two
icons, two data directories, no signature clash, and a rider's recorded rides are never at risk
from a test build.

Two things it cannot do on its own, both device switches rather than code:

- **MIUI / HyperOS refuses installs of a *new* package over USB** until *Settings → Additional
  settings → Developer options → Install via USB* is on. Without it every route returns
  `INSTALL_FAILED_USER_RESTRICTED`, including `adb push` followed by `pm install`. Updating a
  package that is already there, with a matching signature, is allowed — which is why
  `--play` works on the Xiaomi today and the side-by-side `.debug` copy does not.
- **MIUI ignores synthetic input.** `input tap` and `input keyevent` do not reach the app on the
  Xiaomi — not just in the Play Store, everywhere. *Developer options → USB debugging (Security
  settings)* is the switch that permits it. Until it is on, the emulator is where the interface can
  be exercised and the phone is where a build is installed and photographed.

Both are device security settings and have to be set on the phone itself.

## Replacing the rider's own copy

A locally built APK can never update a Play install: Play App Signing re-signs every release with
Google's key, and ours is only the upload key, so `adb install` returns
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`. The way around it is to fetch the artefact Google itself
signed:

Play Console → **App bundle explorer** → pick the version → **Downloads** → **Signed, universal
APK**. That file carries `CN=Android, O=Google Inc.` — verify with
`apksigner verify --print-certs` — so `tools/ship.sh --play <file>` installs it straight over the
Play copy, in place, with the rider's rides and downloaded maps intact and without anyone touching
the Play Store.

## Writing a page

Keep it to what a person needs six weeks later: the symptom, the cause, the fix, and the evidence.
"Verified" means a screenshot, a log line or a test — name it. If something was left undone, say
so; a page that only lists wins is the one nobody trusts when hunting a regression.

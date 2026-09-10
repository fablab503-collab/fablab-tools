# Contributing

Pull requests are welcome, including small ones. Everything here is MIT licensed; by opening a PR
you agree your contribution goes out under the same licence.

## The one thing to know first

**There is no Android toolchain on the machine this was built from.** No JDK, no Gradle, no Android
Studio. GitHub Actions is the only compiler. That shapes everything:

- Every push to `main` under `velotrack/` builds an APK and an AAB, publishes them to the
  `velotrack-latest` release, and uploads to the Play internal and closed testing tracks. A push is
  a release. Documentation and store files are excluded from the trigger on purpose — see the
  `paths` filter in `.github/workflows/velotrack-android.yml`.
- If you *do* have a local toolchain, you are ahead of us: build before you push and say so in the
  PR, because otherwise a broken build costs a five-to-eight minute CI round.

## Before you open a PR

- Keep the change to one thing. A PR that fixes a bug and restyles a screen is two PRs.
- Unit tests live under `velotrack/app/src/test/`. CI runs `testDebugUnitTest` on every build, so a
  failing test blocks the release. Add one if the change has logic in it.
- Match the surrounding code. Kotlin, Views with ViewBinding, Material 3, no Play Services.
- **Do not add a network call.** The app holds `INTERNET` for the map download screen and nothing
  else, `MapLibre.setConnected(false)` is set, and CI greps the merged manifest to keep it that way.
  Anything that phones home while riding will be rejected.
- **Do not add analytics, crash reporting or ads.** The privacy claim in the store listing is a
  promise, not marketing.
- **Do not add a donation link inside the app.** Google Play forbids in-app billing for donations
  *and* links out to Patreon, Ko-fi, GitHub Sponsors or PayPal. StreetComplete was made to strip
  exactly those. Support links belong in this repository, not in the APK.

## Testing what you changed

There is a burn test: `velotrack/tools/burn-test.sh --device <serial>`. It cold-starts the app,
watches the download process's memory, throws 6000 random events at it and reports crashes, ANRs and
OOMs. It needs a real terminal — adb cannot reach its own server inside the sandbox this repo is
usually developed in.

Pass `--device` explicitly. With a phone plugged in alongside an emulator, the phone is usually first
in `adb devices`, and pass 4 is 6000 random taps.

## Reporting a bug

Include the build number (Settings → About), the device, and what the map was doing. If it is a map
data problem — a missing road, a wrong name — that is almost always OpenStreetMap rather than the
app, and fixing it there fixes it for everyone. See
[velotrack/docs/OPENSTREETMAP.md](velotrack/docs/OPENSTREETMAP.md).

## Contributing to the map instead

Honestly, this is worth more than most code changes. The app is a viewer; the map is the product,
and it is made by people adding what they see. Start at
[velotrack/docs/OPENSTREETMAP.md](velotrack/docs/OPENSTREETMAP.md).

---
name: shipping-and-iterating
description: Use when a mobile app already has a first beta out (built with idea-to-beta) and the work has shifted to an ongoing loop of small fixes and features that need to reach a real tester's phone quickly - local builds without CI, verifying on an emulator, updating a device without going through the store, writing a durable changelog, and adding a new feature that needs the network for the first time.
---

# Shipping and iterating: the loop after the first beta

## Overview

`idea-to-beta` gets an app from a spoken idea onto a real phone once. This skill is what happens
for the next thirty builds: a rider reports something, or asks for a feature, and it needs to be
fixed, verified and back on their phone the same session — without waiting on a CI round trip
every time, and without losing track of what changed and why.

**REQUIRED BACKGROUND:** `build-1` (toolchain facts) and `idea-to-beta` (the arc this continues)
if available. Learned building VeloTrack, builds 40–44.

## The premise `idea-to-beta` gets wrong once a beta exists

That skill assumes no local toolchain and CI-only builds. Check that assumption before accepting
it: a machine that "has no Java or Android SDK" may still have Android Studio installed, and
Android Studio bundles both. `find /Applications -iname '*Android Studio*'`, then
`~/Library/Android/sdk` for the SDK and `.../Android Studio.app/Contents/jbr` for the JDK
(`/usr/bin/java` being a stub is not proof there is no JDK anywhere). If both exist, a local build
is 8 seconds incremental instead of a 3-minute CI round trip — verify this before doing thirty
builds the slow way.

Gradle needs an unsandboxed shell to run (it binds a local socket for file-lock coordination) —
same constraint as `adb`. Run it through whatever channel already exists for that in the project
(see `build-1`), not the agent's own sandboxed shell.

## The device-update problem, and its three distinct failures

A locally built debug APK cannot update an app the rider installed from the store: Play App
Signing re-signs every release with Google's key, and a locally built one carries the developer's
upload key. Two ways around it, both worth having:

1. **Install alongside, not over.** Give the debug build type an `applicationIdSuffix` (e.g.
   `.debug`). It becomes a second app, own data directory, cannot conflict with the store copy.
   Good for verifying a fix looks right on the rider's actual device before it goes out for real.
2. **Replace the rider's real copy.** Download the **Signed, universal APK** for the build from
   the store console's bundle/artifact explorer (not the "Original file" — that one is still
   signed with the upload key). Verify with
   `apksigner verify --print-certs`, expect `CN=Android, O=Google Inc.`, and `adb install -r` it.
   This carries Google's own signature, so it updates the store install in place, data intact, no
   tap needed on the device, no store review wait.

If nobody has uploaded this build to the store's testing track yet, do that first (same "hash-blob
→ mktree → commit-tree → push to a throwaway branch → in-page `fetch()` in the console's upload
form → delete the branch" route as `idea-to-beta`, if the artifact is too big for the extension's
own upload) — the just-created release's bundle explorer is where the signed APK comes from.

Three failures look similar and are not:

| Error | Cause | Fix |
|---|---|---|
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Trying to overwrite a store install with a differently-signed APK | Use the Play-signed universal APK instead |
| `INSTALL_FAILED_USER_RESTRICTED` | The OEM (MIUI/HyperOS and similar) blocks installing a *new* package over USB | Only the device owner can flip Developer options → "Install via USB" — do not attempt to work around it |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | The OS refuses to go backwards | Uninstall (destroys app data) — usually not worth it just to reproduce an old build |

Updating an *existing* package with a matching signature is not blocked by the second row even
when a brand-new package install would be — that distinction is what makes the Play-signed-APK
route work at all on a locked-down device.

## A completed build is not a shipped fix

A build that compiles, passes tests and installs is not done. Before calling a fix finished:

1. **Reproduce the original report on a device**, not just "the code looks right." A crash
   reported at a specific screen needs that exact screen exercised, ideally the same input that
   triggered it originally.
2. **Prove the fix with the system's own evidence**, not an assumption from reading the diff — a
   `dumpsys` diff that a haptic fired, a crash-buffer read since a timestamp marker (never
   `logcat -c`, which hides anything before the clear) that stays empty, a screenshot of the
   actual failing case now succeeding.
3. **Write it down before moving to the next thing.** One markdown page per build in a
   `docs/updates/` (or similar) folder: what a user notices, why (quote the report), what changed
   (file list), how it was verified (name the actual evidence), and an honest "not checked"
   section. This is not ceremony — it is the fastest path back to a cause when the same symptom
   comes back three weeks later under a different report. If the project's CI already excludes
   doc-only paths from producing a release, these pages cost nothing to write.

## Adding a feature that touches the network for the first time

An app that has been proudly offline is a different kind of change target than a fresh one. When a
request adds a feature that needs the network (weather, address search, anything hitting a public
API):

- **State the tension, then build it anyway if asked twice.** "This app has promised no data
  connection needed; this feature needs one, scoped to only fire when you use it" is one sentence,
  not a reason to refuse or silently narrow scope.
- **Public APIs police their User-Agent.** A library's default UA (`okhttp`, `Dalvik`, `Java`) gets
  a flat 403 from some services (met.no explicitly bans it) and violates the spirit of others'
  usage policies even where it does not get blocked outright (Nominatim). Send one real,
  identifying string, shared by every such call in the app, not decided per-feature.
- **Read the usage policy before writing the client**, not after something gets rate-limited: rate
  ceiling, whether autocomplete-as-you-type is banned (Nominatim: yes — fire on submit, never on a
  text watcher), what counts as bulk use.
- **Update every place that promised "no network"** in the same commit: README, privacy policy,
  store listing if it makes the same claim. A stale privacy promise is a compliance problem, not
  just an inconsistency.
- **Cache the last good reading** for anything that renders on every screen open (a weather chip,
  not a one-off search) so the UI has something to show before the first fetch of a session
  completes, and so a rider without a connection sees the last thing that worked instead of a gap.

## The JSON-parsing test trap

If the app parses JSON with `org.json` and unit tests run against the plain Android SDK jar (no
Robolectric), check whether `unitTests.isReturnDefaultValues = true` is set before writing the
first real assertion against parsed data. That flag makes every stubbed Android method — including
every `org.json` method — silently return `null`/`0`/`false` instead of throwing, so a test can
pass by asserting "parses to null" against input that should parse to real data, and a positive
case fails with a bare NPE two lines later with no obvious cause. Fix once, project-wide: add
`org.json:json` (the real implementation) as a `testImplementation` dependency — it shadows the
stub on the test classpath. Suspect this immediately if a JSON-parsing test's failure makes no
sense against code that looks obviously correct.

## Do not mistake host overload for a code bug

An `ActivityManager: ANR ... Input dispatching timed out` with `Load: 15+` and CPU pressure output
in the same report is the emulator's host machine struggling, not the app's code — especially if
it reproduces on a plain screen tap with no feature-specific code path involved. Confirm before
writing it up as a finding: does an unrelated, previously-proven-working interaction ANR the same
way right now? If yes, close the app, let the host settle, and say plainly in the changelog that a
check was blocked by environment load rather than recording a false defect or silently skipping
the check without saying so.

## `uses-feature required="true"` is a store filter, not a hint

A `<uses-feature>` left at the default `required="true"` silently removes whole classes of device
from the Play catalogue, and nothing in the build or the tests will ever mention it. VeloTrack
declared `android.hardware.location.gps` as required and had, without anyone noticing, been
excluding every Chromebook, every headset and the large share of tablets with no GNSS chip:
15,332 eligible devices where 20,862 was available, a quarter of the catalogue gone for one
attribute.

The Play Console names the damage exactly. On a release's **Preview and confirm** step, the
**Changes to your supported devices** table breaks the count down by form factor - phone, tablet,
TV, wearable, car, Chromebook, XR - with a "newly supported" column. Read it on every release
where the manifest changed; it is the only place the filter is visible, and it turns a vague
"should work everywhere" into a number to put in the changelog.

Before flipping a feature to `required="false"`, check what the code actually does when the
hardware is absent, and say so in the commit rather than assuming. A permission-driven feature is
often already defensive for unrelated reasons - a nullable `getSystemService`, a `try/catch`
around the registration call - in which case the flag is genuinely the only blocker and the change
is one line. If it is not defensive, make it so first; shipping to a device where the app crashes
on launch is worse than not shipping to it.

Two traps in the same move. Declaring `android.hardware.touchscreen` optional is what Chromebooks
need, but it also makes the app eligible for Android TV, where a bike computer makes no sense -
without a `LEANBACK_LAUNCHER` intent filter it stays out of the TV store's browsable listing, so
note it rather than reverting the flag. And relaxing a feature flag does nothing for form factors
that need their own module: Wear OS stays at zero devices no matter what the manifest says,
because a watch needs a watch-sized interface, not permission to install a phone one.

Verify the merged result, never the source: `aapt2 dump badging <apk>` prints
`uses-feature-not-required:` lines and the `supports-screens:` buckets from the packaged manifest,
which is what Google actually reads.

## Prove a layout on the narrowest screen, not the one on your desk

`adb shell wm size 480x800` and `adb shell wm density 160` turn any emulator into a 320 dp device
- the narrowest width Android supports - in two commands, with `wm size reset` / `wm density reset`
to undo. This is far faster than creating an AVD and it catches the real failure, which is a large
fixed `sp` text size or a fixed-width panel overflowing a narrow screen. Two things to know: the
density change restarts the app, so a screenshot taken six seconds later is still the splash
screen and proves nothing - wait for `dumpsys activity activities | grep ResumedActivity` to name
your activity first. And a forced resize can leave a MapLibre GL surface blank; confirm any
rendering oddity at native resolution before recording it as a bug, because a manifest-only change
cannot possibly have caused it.

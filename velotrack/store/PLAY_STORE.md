# Publishing VeloTrack on Google Play

## Status (8 September 2026)

Done in the Play Console (developer account "danielgmadac", app id 4974445438640549327,
package `com.fablab503.velotrack`):

- App created ("VeloTrack: offline bike GPS", free, app), all App content declarations filled
  (privacy policy, app access, ads, content rating IARC "All other app types" with all "No",
  target audience 18+, data safety "no data collected", advertising ID no, government no,
  financial no, health "Activity and fitness", foreground services data sync + location with the
  screen recording as demo video), store listing with texts, icon, feature graphic and six
  screenshots, category Maps & navigation, contact e-mail.
- **Internal testing**: release 26 (1.0.26) is live. Testers list "VeloTrack testers"
  (hello@danielscreatesparis.com, fablab503@gmail.com). Join link:
  https://play.google.com/apps/internaltest/4701706777077520379 (open it on the phone with one
  of those Google accounts, accept, then install from Google Play).
- **Closed testing (Alpha)**: release 26, all countries, same tester list.
- **Google's review of all 15 changes finished on 8 September 2026 and was approved.** Both the
  internal and the closed Alpha track show release 26 as "Available on Google Play", full roll-out.
  Nothing is pending in Publishing overview.

Still to do, by the account owner:

1. Recruit at least 12 testers, add their Google e-mails to the "VeloTrack testers" list
   (Play Console -> Testing -> Closed testing -> Alpha -> Testers), share the opt-in link shown
   there, and keep the test running for 14 days with 12 opted-in testers.
2. Then Dashboard -> "Apply for production", answer Google's questions about the test, and
   promote release 26 (or a newer build) to Production.
3. New builds: every push to `main` produces `velotrack-latest.aab`; upload it to the track with
   "Create new release" (Add from library after uploading) and roll out.

Everything Play Console needs is in this folder; this file is the order to do it in. Google
requires the forms below to be filled in the Console by the account owner: they cannot be
submitted from a script.

## What is ready

| Item | Where |
|---|---|
| Android App Bundle (`.aab`, signed with the upload key) | `velotrack-latest.aab` on the [velotrack-latest release](https://github.com/fablab503-collab/fablab-tools/releases/tag/velotrack-latest) (also `velotrack-v<N>.aab` per build) |
| App icon 512 x 512, feature graphic 1024 x 500, six phone screenshots 1080 x 2160 | `store/graphics/` |
| Title, short and full description, release notes, category | `store/listing.md` |
| Privacy policy URL | https://github.com/fablab503-collab/fablab-tools/blob/main/velotrack/PRIVACY.md |
| Package name | `com.fablab503.velotrack` (versionCode = GitHub run number) |

## Steps in Play Console (https://play.google.com/console)

1. **Create app**: name "VeloTrack: offline bike GPS", default language English (United Kingdom),
   App, Free. Accept the developer programme policies and US export laws declarations.
2. **Set up your app** (dashboard checklist):
   - Privacy policy: the URL above.
   - App access: "All functionality is available without special access".
   - Ads: No ads.
   - Content rating: fill the IARC questionnaire as a Utility / Productivity app; no user-generated
     content, no violence, no purchases, no location sharing with others. Expected rating: Everyone / PEGI 3.
   - Target audience: 18 and over (simplest; the app is not designed for children).
   - News app: No. COVID-19 contact tracing: No. Government app: No. Financial features: No.
     Health: No (a bike computer is a fitness aid, but it makes no health claims; if the form asks,
     it does not collect health data).
   - Data safety: **Location (precise): collected, not shared, processed ephemerally / stored on
     device only, required for app functionality**. No other data type. No encryption in transit
     claim needed (data never leaves the device). Users can request deletion by uninstalling.
   - Advertising ID: not used.
3. **Store listing** (Grow -> Store presence -> Main store listing): paste the texts from
   `store/listing.md`, upload `icon-512.png`, `feature-graphic.png` and the six screenshots
   (phone). Category Maps & Navigation, contact e-mail, website.
4. **App content -> Foreground service permissions**: the app declares
   `FOREGROUND_SERVICE_LOCATION` (ride recording with the screen off) and
   `FOREGROUND_SERVICE_DATA_SYNC` (map downloads). Explain both, and attach a short screen
   recording of starting a recording and locking the screen (record it on the emulator or phone
   and upload it to a Drive/YouTube link as the form requests).
5. **Release**: Test and release -> Testing -> Internal testing -> Create new release. Play App
   Signing: let Google manage the signing key (our CI key becomes the upload key). Upload the
   `.aab`, paste the release notes, review, roll out to internal testers (add your own e-mail
   under Testers). Install from the opt-in link to confirm.
6. **Production**: personal developer accounts created after November 2023 must first run a
   **closed test with at least 12 testers for 14 days** before Production is unlocked. Create a
   closed testing track, invite testers, wait, then "Apply for production access" and promote the
   same bundle to Production. Review usually takes a few days for a first release.

## Updating later

Every push to `main` produces a new `.aab` with a higher versionCode. Upload it to the same track
and roll out; the Play Console keeps the store listing.

## Automated uploads to the internal test track

The build workflow already contains the upload step. It stays dormant until the repository secret
`PLAY_SERVICE_ACCOUNT_JSON` exists; from then on every push to `main` that changes app code puts the
new bundle straight onto the internal testing track, with the commit subject as the release note.

Creating the key is the account owner's job, because it needs the Play Console and Google Cloud:

1. Play Console -> Setup -> API access -> link (or create) a Google Cloud project.
2. In that project, create a service account. Google Cloud takes you to IAM; no Cloud role is
   needed, so create it and come back.
3. On the service account, create a key of type JSON and download it. Treat the file like a
   password: anyone holding it can publish as you.
4. Back in Play Console -> Users and permissions, invite the service account's e-mail address and
   grant it, for this app only, "Release to testing tracks" (Release manager also works).
5. Paste the whole JSON file into a new repository secret named `PLAY_SERVICE_ACCOUNT_JSON`
   (GitHub -> Settings -> Secrets and variables -> Actions -> New repository secret).

The first upload through the API must follow at least one manual upload of the same package, which
has already happened, so it will work immediately. Promoting a build from internal testing to the
closed or production track stays a manual step in the Console.

## About the "no debug symbols" warning

Play shows this warning on every upload and it cannot usefully be fixed here. The Kotlin side is
already covered: R8 obfuscation is on and the bundle carries
`BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map`, so crash reports from testers
come back with real class and method names.

The warning refers to the native library, `libmaplibre.so`. MapLibre publishes it already stripped:
it has no symbol table and no debug sections, only the dynamic symbol table. Turning on
`ndk.debugSymbolLevel` in Gradle would make the build download a full Android NDK and then package
a symbol file with nothing in it. That trade is not worth taking, so the warning stays.

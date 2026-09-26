# Publishing Bouclier on the App Store

How the App Store build of Bouclier is made. You only need this if you want to publish your own
build under your own Apple Developer account; to use Bouclier yourself, `./build.sh` is enough.

## 1. Xcode

Xcode 26 or later, signed in with the Apple ID of a paid Apple Developer Program membership
(Xcode › Settings › Accounts). `build.sh` picks the paid team first; `TEAM_ID=XXXXXXXXXX ./build.sh …`
forces one. Change `BUNDLE_ID` at the top of `build.sh` to an identifier of your own.

## 2. Build and test

```sh
./build.sh --lists      # fresh filter lists and rules (no Xcode needed)
./build.sh --app        # Mac app, installed in /Applications
./build.sh --sim        # iPhone app in the iOS Simulator
```

In Safari on the Mac: Settings › Extensions › turn on Bouclier › Edit Websites… › Allow.

## 3. Upload

```sh
./build.sh --release    # fresh lists, archives the Mac and iOS apps, uploads both
```

The Mac and the iOS app share one bundle ID, so one purchase covers both (universal purchase).

## 4. The store page

- `listing.json` holds the name, subtitle, promotional text, description, keywords and review notes
  in English and French; `python3 check_listing.py` checks Apple's length limits.
- `support.html` is the support page and privacy policy (host it anywhere, for example GitHub Pages).
- Screenshots: `node tools/screenshots/capture.mjs` captures the real extension pages in headless
  Chromium, `python3 tools/screenshots/compose.py` puts them in phone, tablet and laptop frames at
  Apple's sizes (iPhone 6.9" 1320×2868, iPad 13" 2064×2752, Mac 2880×1800).
- App Privacy: "Data Not Collected". Bouclier has no account, no analytics and no network calls of
  its own; Safari applies the rules.
- `tools/asc.py` can fill the store page through the App Store Connect API (`status`, `fill`,
  `attach`) with an API key of your own in `~/.appstoreconnect/`. Download the key in a desktop
  browser: Apple lets you download it only once.

## 5. After release

New filter lists reach App Store users only through app updates: bump `version` in
`extension/manifest.json`, run `./build.sh --release`, submit. The settings page warns users when
their lists are more than 45 days old.

# fablab-tools

Free, open-source apps and utilities from FabLab 503, built with Claude Code and published from
GitHub Actions. Everything here is MIT licensed: use it, change it, share it.

## Airplane Mode

The sixty second commercial for VeloTrack: the positioning, a shot-by-shot board and the
generation prompts behind it. **[Read it here](https://fablab503-collab.github.io/fablab-tools/)**,
or in [docs/index.html](docs/index.html).

## VeloTrack

**[velotrack/](velotrack/)** is an offline cycling map and ride recorder for Android.

- 3D course-up vector map that works with **GPS only**: no data connection while riding. Maps are
  downloaded inside the app — 10 km of every street, 100 km, 1000 km, the world, or any country,
  cut straight out of the Protomaps planet file. A country too big to take in one go is offered in
  parts rather than refused.
- Records rides of any length (auto-start when you set off, auto-pause when you stop), exports GPX,
  shows lifetime statistics. An interrupted ride can be carried on; a gap in the signal is drawn as
  a dashed line instead of a road you never rode.
- Home, Work and favourite places with straight-line guidance, set by searching an address, and the
  name of the street or park you are on.
- Minimal riding mode above 5 km/h, energy saver on one tap of the battery reading, day/night theme
  by sunrise and sunset, dark OLED-friendly style.
- No accounts, no analytics, no ads, no Google Play Services. Built for battery life.

**Install:** download `velotrack-latest.apk` from the
[Releases](https://github.com/fablab503-collab/fablab-tools/releases/tag/velotrack-latest) page
on your phone and open it. Full guide, screenshots, settings and how it is built:
[velotrack/README.md](velotrack/README.md).

## Repository layout

| Folder | Content |
|---|---|
| `velotrack/` | The Android app (Kotlin), its tools and third-party notices |
| `.github/workflows/` | CI: build, test, sign and publish the APK on every push; map extraction on demand |
| `docs/superpowers/` | Design briefs and plans written before each feature round |
| `assets/icons3d/` | 3D icon library (CC0, from 3dicons.co) used for illustrations |
| `skills/` | The Claude Code skills that describe how this project is built and tested |

## The map is made by people

VeloTrack draws OpenStreetMap. Every road, path and shop name in it was added by somebody who went
past and wrote it down. If a road near you is missing or wrong, fixing it in OSM fixes it for
everyone who ever downloads that area — in this app and in every other one.

**[How to contribute to OpenStreetMap →](velotrack/docs/OPENSTREETMAP.md)** — ten minutes to your
first edit, what you must never copy, which editor to use on a bike, and what to do with the GPX
files this app exports.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Builds run on GitHub Actions only, so you do not need
Android Studio to contribute: push a branch and read the workflow log. Pull requests from forks
build a debug-signed APK because the release signing key is a repository secret.

## Supporting the work

VeloTrack is free, has no ads, no subscription and no analytics, and it is going to stay that way.
If it is useful to you and you want to put something behind it, the links live here on GitHub:

<!-- Set these up and uncomment. GitHub Sponsors: enable it on the fablab503-collab account, then
     add .github/FUNDING.yml so the Sponsor button appears on the repo. -->
- GitHub Sponsors — *not set up yet*
- Ko-fi — *not set up yet*

**These links must never appear inside the app or the Play listing.** Google Play forbids in-app
billing for donations and equally forbids linking out to Patreon, Ko-fi, GitHub Sponsors or PayPal.
StreetComplete, an OpenStreetMap app much like this one, was made to remove exactly those links.
Keeping support on GitHub only is what keeps the Play release safe.

## Licence

MIT, see [LICENSE](LICENSE). VeloTrack bundles third-party components under their own licences,
listed in [velotrack/THIRD_PARTY.md](velotrack/THIRD_PARTY.md). Map data is (c) OpenStreetMap
contributors (ODbL).

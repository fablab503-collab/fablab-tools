# fablab-tools

Free, open-source apps and utilities from FabLab 503, built with Claude Code and published from
GitHub Actions. Everything here is MIT licensed: use it, change it, share it.

## Airplane Mode

The sixty second commercial for VeloTrack: the positioning, a shot-by-shot board and the
generation prompts behind it. **[Read it here](https://fablab503-collab.github.io/fablab-tools/)**,
or in [docs/index.html](docs/index.html).

## VeloTrack

**[velotrack/](velotrack/)** is an offline cycling map and ride recorder for Android.

- 3D course-up vector map that works with **GPS only**: no data connection while riding, maps
  are downloaded in the app for any radius (10 km, 100 km, 1000 km, the world, or France) straight
  from the Protomaps planet file.
- Records rides of any length (auto-start when you set off, auto-pause when you stop), exports GPX,
  shows lifetime statistics.
- Home, Work and favourite places with straight-line guidance, and the name of the street or park
  you are on.
- Minimal riding mode above 5 km/h, day/night theme by sunrise and sunset, dark OLED-friendly style.
- No accounts, no analytics, no Google Play Services. Built for battery life.

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

## Contributing

Open an issue or a pull request. Builds run on GitHub Actions only, so you do not need Android
Studio to contribute: push a branch and read the workflow log. Pull requests from forks build a
debug-signed APK because the release signing key is a repository secret.

## Licence

MIT, see [LICENSE](LICENSE). VeloTrack bundles third-party components under their own licences,
listed in [velotrack/THIRD_PARTY.md](velotrack/THIRD_PARTY.md). Map data is (c) OpenStreetMap
contributors (ODbL).

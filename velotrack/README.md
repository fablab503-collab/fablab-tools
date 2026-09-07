# VeloTrack

VeloTrack is a personal Android app for cycling. It shows where you are on an **offline vector
map** viewed from a tilted, course-up 3D perspective, **records rides** of any length to GPX with
live speed, distance, moving time and climb, and can follow a **pre-planned GPX route** and warn
you when you leave it.

It uses **only the GPS receiver**. The app has no internet permission at all, so it can never
touch Wi-Fi or mobile data, and it is built to be as cheap on battery as possible so a big phone
lasts a multi-day ride. Everything is built and published by GitHub Actions: every push to `main`
produces an installable APK.

Requirements: Android 8.0 (API 26) or newer, a GPS receiver, roughly 100 MB to 2 GB of free
storage per map region.

## Install

1. Open the repository's **Releases** page on the phone.
2. Open the pre-release named **velotrack-latest** (always the newest build) and download
   `velotrack-latest.apk`. Numbered releases (`velotrack-v123`) are the same builds frozen per run.
3. Open the downloaded file. Android asks to allow installing apps from your browser (or Files
   app) the first time; allow it. Updates install over the previous version because every build
   is signed with the same key.
4. On first start grant the **location** permission ("While using the app" is enough) and, on
   Android 13+, **notifications** (the recording notification is how Android keeps the recording
   alive with the screen off).

## Get a map

Maps are prepared at home, once per region, by a GitHub workflow, then copied to the phone.

1. Go to [bboxfinder.com](http://bboxfinder.com/), draw a rectangle around the area you ride in
   and copy the coordinates in the order **minLon,minLat,maxLon,maxLat** (the site shows this
   order as "Lon/Lat"). A region of 100 x 100 km at full detail is typically 100-400 MB; a whole
   small country can reach the 2 GB limit, so split large areas or use `maxzoom` 14.
2. In the repository open **Actions** -> **velotrack-map-extract** -> **Run workflow**. Enter a
   short `name` (lowercase letters, digits, hyphens, e.g. `alps-west`), paste the `bbox`, keep
   `maxzoom` 15 unless the region is large, leave `build` empty to use the newest planet build.
3. Wait for the run to finish (a few minutes for a city, up to an hour for a large region). The
   run summary lists the download links; the files are attached to the release **map-\<name\>**.
4. On the phone, download **`<name>.mbtiles`** from that release (the `.pmtiles` file is a
   secondary copy; the app can load it too, but MBTiles is the tested format).
5. In VeloTrack: **Menu -> Map files -> Import**, pick the downloaded file. The file is copied
   into the app's own storage (a progress bar shows the copy), and becomes the active map. You can
   import several regions and switch between them from the same screen; delete the download from
   your Downloads folder afterwards to free space.

Running the workflow again with the same `name` replaces the files on the same release, so you
can refresh a region with newer OpenStreetMap data whenever you like.

## Ride

- **Airplane mode is recommended.** GPS works in airplane mode, and with all radios off the phone
  lasts much longer. The app itself never uses the network, so nothing is lost.
- **The first fix can take a while.** Without internet there is no assisted GPS, so a cold start
  can take from 30 seconds to a few minutes with a clear view of the sky. Start the app while
  you get ready. Later fixes are fast.
- Tap **Record** to start a ride. Recording runs in a foreground service and **continues with the
  screen off** and when you switch apps; the notification shows distance and time and has Pause
  and Stop buttons. Auto-pause stops the clock when you stop moving (configurable).
- The map follows you in **3D course-up** by default. Drag the map to look around (the camera
  goes to Free mode); tap **Recenter** to follow again. The 2D/3D button switches to a north-up
  view.
- The HUD shows speed, distance, moving time, average moving speed, elevation gain, the time,
  GPS quality (satellites used / seen and accuracy) and battery.
- **Battery Saver:** on many phones Battery Saver stops GPS when the screen is off, even for a
  recording app. The app warns you when you start recording with Battery Saver on. Either turn it
  off for the ride or exempt VeloTrack in the system battery settings.
- If the app or the phone dies during a ride, the next start offers to **resume** the unfinished
  track (a new segment continues the same ride) or finish it.
- **Routes:** Menu -> Import route (GPX) to load a planned route (`rte` or `trk` files from any
  planner); Menu -> Clear route removes it. It is drawn in orange, the HUD shows the distance remaining, and the phone vibrates
  when you are more than the off-route distance away for 10 seconds, and once more when you are
  back on the route.
- **Tracks:** Menu -> Tracks lists your rides. Tap one to see it on the map; long-press to rename,
  export (share, or save as a `.gpx` file) or delete.

## Settings

| Setting | Meaning | Default |
|---|---|---|
| Accuracy cut-off | GPS fixes with a worse horizontal accuracy are ignored | 50 m |
| Auto-pause | Pause moving time and storage when slower than walking pace for 10 s | on |
| Screen | Keep the screen on while the map is visible, dim it to a chosen level, or leave it to the system | keep on |
| Units | Kilometres or miles | km |
| 3D pitch | Camera tilt in Follow 3D mode (max 60) | 55 degrees |
| Off-route distance | Distance from the route that counts as off-route | 50 m |

## Privacy

- The APK declares **no `INTERNET` permission**; the CI build fails if the merged manifest ever
  contains it. The app cannot send anything anywhere.
- No Google Play Services, no accounts, no analytics. Rides, routes and map files live only on
  the phone, in the app's private storage, until you export them yourself.
- Location is used only while the app is visible or a recording is running (a persistent
  notification is shown in that case). Background location permission is never requested.

## Building locally

The normal way to build is to push to `main` and let the `velotrack-android` workflow do it. To
build on a computer you need Android Studio (or the Android SDK plus JDK 17), Gradle 9.7 or newer
(there is no wrapper in the repository), Node.js 22 or newer and `curl`.

```sh
cd velotrack
tools/build-assets.sh          # generates the style and downloads fonts and sprites (~12 MB)
gradle testDebugUnitTest assembleRelease
```

`tools/build-assets.sh` must run before the first Gradle build: the style, glyphs and sprites in
`app/src/main/assets/` are generated, not committed. Set `VELOTRACK_LANG=de` (or any code from the
Protomaps list, e.g. `fr`, `pt`, `zh-Hans`) to get labels in another language; without it the
script uses the language part of the system `LANG` variable (`de_DE.UTF-8` -> `de`; `C`/`POSIX`
and unset -> `en`), so CI builds are English with the local name as fallback. Release builds are
signed with the keystore in `keystore/` unless `KEYSTORE_FILE`, `KEYSTORE_PASSWORD` and `KEY_ALIAS` are set in the
environment.

## Limitations

- No turn-by-turn navigation: a route is a line on the map with distance remaining and an
  off-route warning. No elevation profiles, sensors (heart rate, cadence, barometer, compass) or
  cloud sync.
- Maps must be prepared on GitHub and copied to the phone; the app cannot download anything.
- Label language is fixed at build time (`VELOTRACK_LANG` or `LANG` when running
  `tools/build-assets.sh`, see above). Devanagari labels are not bundled.
- Elevation comes from GPS (mean sea level on Android 14+ when the phone provides it, otherwise
  the raw WGS84 height), so climb figures are approximate.
- `.pmtiles` files can be loaded but MapLibre's PMTiles reader has open bugs with gzip-compressed
  archives (which Protomaps extracts are); prefer the `.mbtiles` file.

## Licences

App code: MIT (see the repository licence). Map data is (c) OpenStreetMap contributors, licensed
under the ODbL: https://www.openstreetmap.org/copyright. The map style, fonts, sprites and
libraries used by the app and their licences are listed in [THIRD_PARTY.md](THIRD_PARTY.md).

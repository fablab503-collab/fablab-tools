# VeloTrack privacy policy

Last updated: 9 September 2026. Applies to the VeloTrack Android app (package `com.fablab503.velotrack`) published by FabLab 503.

## The short version

VeloTrack does not collect, store or share any personal data on servers. Everything the app records stays on your phone unless you export it yourself.

## What the app processes, and where

- **Location.** VeloTrack reads your phone's GPS position to show you on the map, record rides, guide you to saved places, name the street you are on and choose the dark or light theme by sunrise and sunset. Location is used only while the app is open or while a ride is being recorded (a persistent notification is shown in that case). The app never asks for background location. Positions are stored only in the app's private storage on your phone.
- **Rides, places and settings.** Recorded tracks, favourite places (Home, Work and the ones you add) and your settings are stored in the app's private database on the phone. They leave the phone only when you export a GPX file or share it, on your own action.
- **Map downloads.** The Download map screen fetches map tiles from `build.protomaps.com` (or a planet file URL you configure). That request contains the byte ranges of the tiles you asked for; it does not contain your position, identity or ride data. The map renderer itself is locked offline.
- **Address search.** Typing an address when setting Home, Work or a favourite and pressing search sends that text to `nominatim.openstreetmap.org`, OpenStreetMap's public lookup service, to find its location. This happens only when you press search, never as you type, and only that text is sent — no position, identity or ride data. Dragging the map to the spot instead needs no network at all.
- **Weather.** The small weather chip next to the clock sends your last known position to `api.met.no`, the Norwegian Meteorological Institute's public forecast service, to show the local temperature and conditions; tapping it opens Yr.no's public forecast page for that position. This refreshes at most roughly every 30 minutes, or when you have moved a fair distance, and never while the app is not open.
- No other screen uses the network, and recording a ride, following a route or exporting a GPX never does.

## What the app does not do

- No accounts, sign-in or user identifiers.
- No analytics, crash reporting, advertising or tracking libraries.
- No data sent to FabLab 503 or to anyone else.
- No access to contacts, camera, microphone, photos or files beyond the GPX files you choose to import or export.

## Permissions

| Permission | Why |
|---|---|
| Precise location | Position on the map, ride recording, guidance, street name, sunrise/sunset theme |
| Notifications | The recording notification that keeps a ride alive with the screen off, and download progress |
| Foreground service (location, data sync) | Recording rides and downloading maps while the screen is off |
| Internet, network state | Map downloads, address search and the weather chip — each only while you use it |
| Vibrate | Off-route alerts |

## Children

VeloTrack is a general-audience utility and is not directed at children under 13.

## Deleting your data

Uninstalling the app deletes all rides, places, settings and downloaded maps. Inside the app you can delete individual rides (Tracks), places (Favourites) and map regions (Map data).

## Changes and contact

Changes to this policy are published in the app's source repository, https://github.com/fablab503-collab/fablab-tools. Questions: open an issue there.

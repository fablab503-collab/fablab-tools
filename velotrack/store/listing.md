# Google Play store listing texts

Copy these into Play Console -> Grow -> Store presence -> Main store listing. Limits: title 30
characters, short description 80, full description 4000.

Written against build 67. Everything claimed here is in that build — if a feature slips, cut the
line rather than shipping a listing that promises it.

**No donation link appears anywhere in this listing, and none may appear in the app.** Google Play
forbids both in-app billing for donations and links out to Patreon, Ko-fi, GitHub Sponsors or
PayPal. StreetComplete — an OpenStreetMap app much like this one — was made to strip exactly those
links out. Donations live on the GitHub page only; see the root `README.md`. Do not "helpfully" add
a Support button to the About screen later.

## App name (30)

VeloTrack: offline bike GPS

## Short description (80)

Offline bike computer. The map lives on your phone. No signal, no account.

## Full description

Most cycling apps stop being useful the moment the signal does. VeloTrack keeps working, because
the map is already on your phone and nothing is ever fetched while you ride.

It is free, open source, and there is no account to make.

WHY THIS ONE
Ride into a valley, a forest, a tunnel or a foreign country with no roaming, and the map is still
there. There is no login, no subscription, no advertising and no tracking. The app talks to the
internet exactly once — when you choose to download a map — and never again while you are riding.

MAPS THAT LIVE ON YOUR PHONE
• Download what you ride in, from inside the app: 10 km of every street, 100 km of main roads, a
  1000 km region, or a whole country. The exact size is shown before you confirm.
• A big country at full detail is offered in parts, so you can take the whole of it or just the
  piece you ride in, instead of being told no.
• Map data is OpenStreetMap, rendered on the phone. Nothing is streamed mid-ride.
• A black-background style for OLED screens at night and a high-contrast one for daylight, switched
  by hand or by sunrise and sunset where you are.

RIDE
• Records rides of any length: speed, distance, moving time, average and climb. It starts on its own
  when you set off and pauses on its own when you stop.
• A ride is never lost. If the phone dies or the app is killed, the ride is still there when you
  come back, and you can carry on with it.
• Riding mode: above 5 km/h the buttons fade away and only the numbers stay. Touch the screen to
  bring them back.
• Energy saver, on a single tap of the battery reading: dims the screen, slows the map and eases off
  the GPS, for the days when getting home matters more than a perfect track.
• Export any ride as GPX, or follow a GPX route with off-route alerts.
• A gap in the recording is drawn as a dashed line, so a lost signal never looks like a road you
  took.

PLACES
• Home and Work, set by searching for an address, with straight-line guidance: a pin, a dashed line,
  and the distance and direction in the header.
• Favourites for anywhere else — people, restaurants, whatever you ride to — with a name and a note.
• The header names the street, park or place you are on, read from the offline map.

WHAT YOU HAVE RIDDEN
• Totals for all time, this year, this month and this week: distance, rides, moving time, climb and
  your longest ride.

PRIVACY AND BATTERY
• No account, no analytics, no ads, no Google Play Services. Your rides never leave the phone.
• Only the GPS receiver is used while riding. It works in airplane mode.
• Open source under the MIT licence: https://github.com/fablab503-collab/fablab-tools

Requires Android 8.0 or newer and a GPS receiver.
Map data © OpenStreetMap contributors, licensed ODbL: https://www.openstreetmap.org/copyright

## Release notes / What's new (500 max, keep the <en-GB> tags in the console)

Rides are harder to lose: one that was interrupted can be carried on, and a forgotten one asks
whether you have finished. A gap in the recording is now drawn as a dashed line instead of a
straight road you never rode. Energy saver dims the screen and eases off the GPS on long days. Set
Home and Work by searching for an address. A country too big to download in one go is now offered
in parts instead of refused.

## Positioning, for anything longer than the listing

One sentence: **the bike computer that still works where your phone doesn't.**

Who it is for: people who ride out of coverage — rural roads, mountains, forests, abroad without
roaming — and people who would rather not hand their movements to a fitness company.

What it is not: a training platform. There are no segments, no leaderboards, no friends, no
coaching. It records where you went and shows you a map, on a phone with no signal, without asking
who you are.

Against the alternatives:
- Strava and Komoot are stronger for training and route discovery, and both want an account and a
  connection for the parts that matter. VeloTrack wants neither.
- Google Maps offline areas expire, cover a limited box and are not a bike computer.
- OsmAnd is the closest free alternative and does far more; VeloTrack does one job with a screen you
  can read at 25 km/h and a battery budget to match.

## Categorisation

- App type: App. Category: Maps & Navigation (alternative: Health & Fitness).
- Tags: cycling, GPS, offline maps, bike computer.
- Contact email: fablab503@gmail.com. Website: https://github.com/fablab503-collab/fablab-tools
- Privacy policy URL: https://github.com/fablab503-collab/fablab-tools/blob/main/velotrack/PRIVACY.md

## Graphics (in store/graphics)

- `icon-512.png`: 512 x 512 app icon.
- `feature-graphic.png`: 1024 x 500 feature graphic.
- `screenshots/*.png`: five phone screenshots, 1080 x 2160, taken on build 67. Play rejects an
  aspect ratio above 2:1 and the emulator is 1080 x 2424, so each one is
  `sips --cropToHeightWidth 2160 1080`. In listing order:
  1. `01-riding-recorded.png` — recording in Paris, speed and distance, the track behind the puck.
  2. `02-map-controls.png` — the same ride with the controls up.
  3. `03-download-map.png` — the download screen, the offline story in one picture.
  4. `04-country-in-parts.png` — France offered in 9 parts.
  5. `05-statistics.png` — lifetime totals.

The statistics shot shows the few kilometres of test riding that were on the emulator. Before this
goes in front of the public, retake it from a phone with real rides on it, or the headline reads
"3.08 km ridden in total".

## Play Console "Events" (Grow -> Store presence -> Promotional content)

Nothing to run yet, and an event with nothing behind it is worse than none. Events need a real
hook — a release worth announcing, a season, a reason to open the app this week. Candidates once
the app is in production:
- **The watch app lands.** A genuine feature launch, once the wear artifact actually ships.
- **Winter riding.** Dark-hours positioning: OLED night style, energy saver, works when the phone
  is cold and the signal is thin.
- **Holiday season.** "Download the country before you fly" — the one thing this app does that the
  others cannot.
Events require the app to be live in production, so none of this applies while it is in closed
testing.

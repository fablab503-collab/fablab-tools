# Feature ideas from Strava, Komoot and Google Maps

Researched 8 September 2026, at the user's request, as a list only — nothing here is built. Each
entry says what the competitor does, whether it fits VeloTrack's actual design (offline, GPS-only,
no account, no data leaving the phone), and roughly how big a job it would be. VeloTrack already
has auto-pause, GPX import/export, Home/Work/favourite guidance with distance and bearing, offline
map bands by region, elevation gain, night mode, and haptic feedback — those are not repeated below.

## Fits the offline, no-account design well

**Turn-by-turn cyclist routing on the downloaded map.** Komoot and Google Maps both route roads and
paths from A to B for a bike, not just show a straight line to a favourite the way VeloTrack's
guidance does now. This is the single biggest gap against both — VeloTrack has an offline vector
map with roads in it already, so the map data exists; what's missing is a routing graph and a
turn-by-turn engine (something like GraphHopper or Valhalla running on-device). Large job, but it
is the feature every rider will eventually ask for once they use Home/Work guidance and expect it
to follow streets.

**Voice announcements.** Both competitors read distance, turns and stats aloud so a rider never
has to look down. VeloTrack already writes the guidance message to a toast; routing it through
Android's `TextToSpeech` as well is a small, self-contained addition — no network, no account,
works entirely offline.

**Climb categorization on a route.** Segment-independent: given a GPX or downloaded route, compute
gradient buckets (Strava-style, or Komoot's difficulty labels) purely from the elevation data
already being tracked. No servers, no comparison to other riders — just local arithmetic on data
VeloTrack already has. Small-to-medium job.

**Round-trip / loop route generator.** Komoot and Strava's route builder can propose a loop of a
target distance from the current position, using popularity data. An offline version — generate a
loop from the local road graph without any popularity signal — is weaker than either competitor
but still useful and fits the no-network design. Depends on the same routing graph as turn-by-turn.

**Weather along the route.** Every third-party app doing this (Epic Ride Weather, myWindsock,
Brezza) fetches a forecast over the network, which conflicts with VeloTrack's no-data-use design.
The honest offline version is smaller: read the phone's last-known weather from a source that does
not need a live connection, or skip it — this is the one item on this list where "fits the design"
and "fits the offline promise" pull in different directions.

**Bike/gear mileage tracker.** Neither Strava's Gear feature nor Komoot has a good local
equivalent, but it needs no network at all: sum recorded ride distances against a bike record kept
in the existing Room database, and remind the rider at a set interval (chain, tyres). Small job,
fully local.

**Crash detection with a local emergency contact alert.** REALRIDER-style: use the accelerometer to
detect a sudden deceleration/impact, start a countdown, and if not cancelled, send an SMS (not
push, not a server round-trip) with the last GPS fix to a contact stored in phone settings. Keeps
the no-account design; the hard part is not the "send it" — SMS from a foreground service is
routine — but tuning the detector so a pothole doesn't cry wolf. Medium-large job, and the kind of
feature that erodes trust fast if it's wrong, so it wants real-ride tuning before shipping.

## Would need a server or account — conflicts with the current design

**Segments and leaderboards (Strava).** Comparing a rider's time on a stretch of road against
everyone else's needs a shared server with everyone's rides on it. VeloTrack's entire pitch is that
nothing leaves the phone; segments are the feature most directly opposed to that.

**Heatmap / popular-route discovery (Strava).** Same problem in reverse — it needs everyone else's
aggregated GPS data to show a rider where other people ride. Could theoretically ship with a
bundled, pre-baked heatmap for a specific region (like the map bands already are), but that is a
data-pipeline project of its own, not a small feature.

**Community route sharing / Highlights (Komoot).** Discovering other people's saved routes needs a
backend to host them.

**Kudos, following, social feed (Strava).** Needs accounts and a server.

**Live location sharing / Beacon (Strava).** Sharing a live position with a friend needs some
channel off the phone — even a privacy-conscious version (e.g., a link that expires) needs a
relay server, unless it piggybacks on something already on the phone like Find My or WhatsApp
location share, which is a different, smaller feature: "export a live-location link to another
app" rather than building sharing infrastructure.

**BLE heart rate / power / cadence sensors.** Not a server problem — this is genuinely local
(`BluetoothLE` pairing, same as Strava's own HR pairing) — but it is a real scope increase: a
device-pairing UI, a reconnection strategy, and a new data column through recording, storage and
GPX export. Worth listing because riders with a chest strap or power meter will ask for it, but it
is its own project, not a quick add.

**Multi-day tour planning with suggested lodging (Komoot Premium).** The routing half fits the
offline design; the lodging-suggestion half needs a places database and is the part that needs a
server or a bundled POI dataset far bigger than what VeloTrack ships today.

## Smaller polish items worth doing regardless

- **Elevation profile chart** for a planned or completed route (Google Maps shows this per route
  option) — pure local computation from data already recorded.
- **"Feels like" summary at ride start** (temperature/wind) — same network conflict as the weather
  item above; skip unless the no-network rule is relaxed for this one read.
- **Lite/overview navigation mode** (Google Maps): a persistent notification with ETA and next
  turn, useful once turn-by-turn exists, not before.
- **Photo/note pins on a route** (Komoot Highlights, personal-only version): attach a photo and a
  short note to a GPS point during or after a ride, stored locally. Fits the design well and is a
  contained addition to the existing favourites/database code.

## Suggested order if any of this gets built

1. Voice announcements (small, no dependencies)
2. Climb categorization (small, uses data already recorded)
3. Elevation profile chart (small, same data)
4. Bike/gear mileage tracker (small, local database only)
5. Photo/note pins on a route (small-medium)
6. Turn-by-turn routing (large — but unlocks the round-trip generator and Lite navigation mode too)
7. Crash detection (medium-large, needs real-ride tuning before it can be trusted)
8. BLE sensor pairing (medium-large, its own subsystem)

Nothing above has been started. This file is a reference for a future planning conversation, not a
plan in itself — see [`docs/superpowers/specs/`](superpowers/specs/) for what a real spec for any
one of these looks like once one is picked.

# OpenStreetMap: where VeloTrack's map comes from, and how to give back

Every road, path, park and shop name in VeloTrack was put there by a person. Not a survey company,
not a satellite — a person who walked or rode past and wrote it down. That is OpenStreetMap, and
this page is about who they are, what the app owes them, and how to become one of them.

## Who "contributors" means

The line in the app and the listing is `© OpenStreetMap contributors`. It is not a formality. OSM
has millions of registered accounts and a few hundred thousand people who edit in any given month,
and the map you ride on is the sum of their evenings. When you fix a missing cycleway near your
house, you become part of that credit line for everyone who downloads that area afterwards.

## What the app owes them (and already does)

OSM data is licensed **ODbL 1.0**. The obligations that matter here:

- **Attribution.** The credit must be somewhere a user actually meets — not buried in a licence
  file. VeloTrack shows `© OpenStreetMap contributors` with a link to
  https://www.openstreetmap.org/copyright. That link is the required one; it is where OSM explains
  its own sources and licence.
- **Share-alike, on the database.** If you improve or derive from the *database*, the improved
  database has to go back out under ODbL. Rendering tiles and drawing them on a screen is a
  "Produced Work" — that is what VeloTrack does, and a Produced Work does not have to be ODbL. The
  app's own code stays MIT.
- **Selling it is allowed.** ODbL is not non-commercial. A paid app using OSM data is fine as long
  as the attribution and share-alike terms are met. That was never the blocker on charging for
  this app — Google's own free-to-paid rule was.

Chain of custody, so it is written down somewhere: OSM contributors → the daily planet build from
**Protomaps** (`build.protomaps.com`, ODbL) → the bytes VeloTrack extracts and stores on the phone.
Full list of every third-party licence in [`../THIRD_PARTY.md`](../THIRD_PARTY.md).

## How to contribute to OpenStreetMap

### Start here, in about ten minutes

1. Make an account at https://www.openstreetmap.org/user/new.
2. Find somewhere you know better than the map does — your street, your usual ride.
3. Click **Edit**. That is **iD**, the browser editor, and it has a walkthrough built in. Take it.
4. Fix one thing. A missing footpath, a shop that closed, a one-way arrow pointing the wrong way.
5. Write a real changeset comment ("added cycleway on Rue de Maubeuge, surveyed by bike") and save.

Your edit is in the database within seconds and in most people's apps within days.

### The rule that gets people banned

**Never copy from Google Maps, Bing, Apple Maps, or any commercial map, app or atlas.** Not the
geometry, not the names, not "just checking a street name". Those are copyrighted databases and
copying from them poisons OSM's licence — edits traced from them get reverted and accounts get
blocked. Aerial imagery is different: the imagery layers offered *inside* the OSM editors are there
because permission was granted, and tracing from those is exactly what they are for.

What you *can* use: your own eyes, your own photos, your own GPS traces, and out-of-copyright or
explicitly open government data.

### Tools, by how you like to work

| Tool | Where | Good for |
|---|---|---|
| **iD** | Browser, the Edit button on osm.org | Everything. Start here. |
| **StreetComplete** | Android | The best on-a-bike option. It asks you simple questions about things near you — "is this path paved?", "does this street have a cycle lane?" — and you answer with a tap. No tagging knowledge needed at all. |
| **Every Door** | Android / iOS | Shops, addresses, opening hours. Fast surveying on foot. |
| **Vespucci** | Android | Full-power editing on a phone. Steep. |
| **JOSM** | Desktop, Java | Big or careful edits, imagery tracing, bulk fixes. Steepest, and worth it eventually. |

For a cyclist, **StreetComplete is the answer**. Ride, stop at a light, answer two quests, ride on.
It cannot break anything — it only asks questions it knows how to write safely.

### Your rides are useful data

VeloTrack exports GPX (Tracks → ⋮ → Export). Those traces can be uploaded to
https://www.openstreetmap.org/traces, where other mappers use them to check whether a path really
exists and where it actually runs. A trace is not an edit — nothing changes on the map by itself —
but a handful of traces along an unmapped track is what lets someone draw it correctly.

Two things before you upload: a trace records where *you* were, so do not upload the ones that start
at your front door, and set the visibility deliberately (**Identifiable** and **Trackable** let the
timestamps be used; **Public** and **Private** strip the link to you).

### Things worth mapping that riders notice and nobody else does

- `surface=` on paths — asphalt, gravel, dirt. Routers use it, and it is the difference between a
  shortcut and a mistake.
- `bicycle=yes/no/designated` and `access=` on ways where a sign says something.
- Barriers: bollards, gates, steps, the kissing gate you had to lift the bike over.
- `lit=yes/no` on paths you would or would not ride after dark.
- Cycle parking, drinking water, repair stands, public toilets.

### When you are stuck or unsure

- **A note, not a guess.** If you saw something but cannot map it properly, drop a Note on
  osm.org — a local mapper will pick it up. Never invent geometry to fill a gap.
- The wiki page for any tag (`wiki.openstreetmap.org/wiki/Key:surface`) tells you what it means.
- The community forum at https://community.openstreetmap.org has a section for most countries, in
  the local language.

## Contributing to VeloTrack itself

See [`../../CONTRIBUTING.md`](../../CONTRIBUTING.md) in the repository root.

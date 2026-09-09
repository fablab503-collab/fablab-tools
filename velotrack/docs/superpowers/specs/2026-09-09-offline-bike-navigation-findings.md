# Offline bike navigation — research findings

**Date:** 9 September 2026. **Status:** research only. Nothing built, nothing decided.
Parked until the week of 15 September 2026.

This is the output of a feasibility spike, not a spec. It exists so the research does not have to
be done twice. The question was: can VeloTrack get Organic Maps-style navigation — search an
address, route to it, turn by turn — on a bike that uses ordinary streets but must never be put on
a road signed 80, 110 or 130 km/h?

## Answer

Yes, and the licensing is better than expected.

## The engine: BRouter

- **MIT licensed** — verified from `LICENSE` in the repo, not from a summary. Same licence as
  VeloTrack, so it can simply be used.
- Pure Java, Android-first, written for cyclists. No NDK.
- On **JitPack**, not Maven Central (`repo1.maven.org/maven2/org/btools/` returns 404; GitHub
  Packages returns 401 without a token):

  | Module | Jar |
  |---|---|
  | `com.github.abrensch.brouter:brouter-core:v1.7.9` | 148 KB |
  | `brouter-mapaccess` | 53 KB |
  | `brouter-util` | 44 KB |
  | `brouter-expressions` | 30 KB |
  | `brouter-codec` | 25 KB |

  ~300 KB in total. Negligible next to the 13 MB of bundled fonts. The MIT licence also allows
  vendoring the source outright if depending on JitPack from CI is unwanted.

**GraphHopper** is the alternative: Apache-2.0, also compatible, but its README says offline
routing "is no longer officially supported", and it imports an OSM `.pbf` on the device. BRouter's
data is pre-built. **Valhalla** is C++/NDK — a bigger commitment than this app needs.

**Not viable:** calling the installed BRouter app over its AIDL service (what OsmAnd and Locus do).
Almost no work, but it means telling riders to install a second app, which contradicts the whole
point of this one.

## The speed rule is a profile, not code

BRouter's `misc/profiles2/lookups.dat` — the table that decides what gets encoded into the routing
files — **contains `maxspeed`**, with entries for 80, 110, 120 and 130 as well as `urban` and
`rural`. Profiles match `tag=value` with `|` alternation, and any cost factor `>= 10000` is treated
as forbidden. So the rule is roughly:

```
assign costfactor = switch maxspeed=80|90|110|120|130  10000
                    switch highway=motorway|trunk       10000
                    switch maxspeed=urban|30|50         1.0
                    ...
```

**Caveat that matters:** no stock BRouter profile uses `maxspeed`, and OSM's coverage of the tag is
patchy — most French D-roads outside towns carry no `maxspeed` at all, because 80 is implicit in
law. A profile that only reads `maxspeed` would happily route onto exactly the roads the rider
asked to avoid. It has to fall back on highway class as well. That is tuning work on real routes,
not a blocker.

## The data is a second download, and it is bigger than the map

BRouter uses pre-built 5°x5° `.rd5` segments from `brouter.de/brouter/segments4/`, rebuilt weekly.

| File | Size | Covers |
|---|---|---|
| `E0_N40.rd5` | 119 MB | Montpellier, Spain, west France |
| `E0_N45.rd5` | 127 MB | |
| `E5_N40.rd5` | 69 MB | |
| `E5_N45.rd5` | 252 MB | France / Germany / Alps |

The whole downloaded map of this area is currently 53 MB, so routing roughly triples it.

## What the tiles already contain (measured, not assumed)

A live z15 Protomaps tile over Montpellier (3.8767 E, 43.6108 N — tile 15/16736/11964, from the
20260909 planet build, the same data `band3` downloads) was fetched over HTTP range requests and
decoded:

| Layer | Features | What is in it |
|---|---|---|
| `buildings` | 3,170 | **501 with `kind=address` and `addr_housenumber`** — but no street name on them |
| `roads` | 351 | **324 named**, with `oneway` and real OSM classes in `kind_detail` (`residential`, `tertiary`, `living_street`, `cycleway`, `pedestrian`) — **no `maxspeed`** |
| `pois` | 2,656 | 1,409 named |
| `places` | 4 | localities and neighbourhoods |

Two conclusions:

1. **Offline place / street / POI search is buildable from data already on the phone.** An FTS index
   built at download time would cover towns, streets and shops. House numbers exist but carry no
   street name, so "12 Rue de la Loge" needs a spatial join to the nearest named road — approximate.
   Organic Maps-grade address search is a project of its own.
2. **Routing off these tiles is not possible.** No topology across tile boundaries, no turn
   restrictions, no speeds, geometry clipped per tile. This is why every offline router ships its
   own graph, and why the 119 MB is unavoidable.

## Shape of the work

| Piece | Sessions | Confidence | Notes |
|---|---|---|---|
| 1. Routing engine + segment download | 2–3 | medium | One job, not two: a router cannot be tested with no data on the device |
| 2. Turn-by-turn + voice | 1 | good | BRouter emits hints itself (`turnInstructionMode`) |
| 3. Search box → route | 1 | good | The Nominatim search already exists |
| 4. Offline search index | 1–2 | low | Means parsing MVT in Kotlin at download time; no such code exists yet |

Roughly a day of sessions for piece 1, a week of evenings for all of it.

Piece 1 is worth doing alone: it plugs into plumbing that already exists — `MapController.setRoute`,
the off-route distance setting, the guidance row, and GPX route import all work today.

**The medium confidence on piece 1 is specific:** BRouter's API is file-path shaped. It wants a
segments directory, a profile file, and writes a track file, because it was written for a desktop
and for its own app. Bending that onto Android's storage rules is where the surprises will be.

## Before any of this

The build does not currently run: Gradle cannot start under this session's sandbox
(`java.net.SocketException: Operation not permitted` from its file-lock handler), so builds 60 and
61 are sitting in the working tree unverified. Adding a new dependency and a new binary data format
while unable to compile would be a bad trade. `tools/ship.sh` green comes first.

## Sources

- BRouter — https://github.com/abrensch/brouter · licence https://raw.githubusercontent.com/abrensch/brouter/master/LICENSE
- Profile language — https://zod.github.io/brouter/developers/profile_developers_guide.html
- Segments — https://zod.github.io/brouter/users/download_segments.html
- Android service — https://zod.github.io/brouter/developers/android_service.html
- GraphHopper — https://github.com/graphhopper/graphhopper
- Protomaps basemap layers — https://docs.protomaps.com/basemaps/layers

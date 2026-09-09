# Build 44 — search an address, and a weather chip by the clock

Date: 9 September 2026. Commit: pending. Tag: `velotrack-v44`.

## What a rider notices

Setting Home, Work or a favourite by dragging the map now has an alternative: a **Search for an
address** box at the top of the picker. Type an address, press the search icon or Enter, and the
map jumps there — the same crosshair-and-drag flow is still there afterwards, in case the search
doesn't land on quite the right spot. This is the one place in the app it applies (Home, Work and
every favourite all open the same picker), so it did not need to be built three times.

Next to the clock, a small chip now shows the local weather: an icon, the temperature, tap it and
the phone opens the official forecast page for that spot. It appears once a reading exists and
updates roughly every half hour, or sooner if the rider has moved a fair distance.

## Why

Both asked for directly, and both are a real, honest exception to how this app has worked so far.

**VeloTrack has never made a network call it wasn't obviously and directly doing something for the
rider** — map downloads and Play uploads, all rider-initiated, all visible as a download bar. Address
search and weather are the same kind of exception in a different place: an address search fires
only when the rider presses search, never per keystroke, and the weather chip only refreshes on the
existing once-a-minute clock tick, never in the background while the app isn't on screen. Nothing
about ride recording, GPX export or offline maps changed; a rider who never touches either feature
never causes a byte to move.

## What changed

| File | Change |
|---|---|
| `download/Geocoder.kt` | New. One Nominatim (OpenStreetMap) lookup per explicit search, with the identifying `User-Agent` and 1 req/sec ceiling its usage policy requires — see the file's own doc comment for exactly which rules and why. |
| `download/WeatherClient.kt` | New. One met.no Locationforecast reading, same institute Yr.no is built on; `officialForecastUrl` is the page the chip's tap opens. |
| `download/NetIdentity.kt` | New. The one User-Agent string both of the above send — met.no returns a hard 403 for OkHttp's own default string. |
| `ui/WeatherIcons.kt` | New. Maps met.no's ~60 symbol codes to one emoji each, matching the visual language the no-map card already uses rather than bundling a new icon set. |
| `ui/PlacePickerActivity.kt`, `layout/activity_place_picker.xml`, `layout-land/activity_place_picker.xml` | The search box, wired to fire only on submit (Enter or the search icon), never on text change. |
| `ui/MainActivity.kt`, `layout/activity_main.xml`, `layout-land/activity_main.xml` | The weather chip: rendered from a cached reading immediately, refreshed on the existing clock tick when stale (30 min) or the rider has moved 20 km. |
| `settings/Prefs.kt` | `WeatherReading` cache (temperature, symbol, the position it was fetched for, when) so the chip has something to show before the first fetch of a session completes. |
| `ui/Format.kt` | `temperature()`, metric/imperial, alongside the existing speed/distance/elevation formatters. |
| `gradle/libs.versions.toml`, `app/build.gradle.kts` | Added `org.json:json` as a **test-only** dependency — see below. |

## The gap this found in the test setup

Writing tests for the new JSON parsing (`WeatherClient.parse`, `Geocoder.parse`) surfaced something
that had been silently true since the first commit: unit tests run against the Android SDK's stub
jar, which only stubs `org.json` — combined with this module's `isReturnDefaultValues = true`, every
`JSONObject`/`JSONArray` call in a unit test was silently returning `null` instead of throwing or
parsing anything. `PlanetSource.kt`'s own JSON parsing (in the app since the first commit) has
therefore never actually been exercised by a passing test. Adding `org.json:json` as a
`testImplementation` fixes this for the new tests and, as a side effect, for any future test of
`PlanetSource` too. Worth remembering: a green JSON-parsing test in this module proves nothing
unless this dependency is present.

## How it was checked

- New tests: `WeatherClientTest` (6), `GeocoderTest` (3), `WeatherIconsTest` (5),
  `FormatTemperatureTest` (2) — all against real JSON parsing once the fix above landed. Full clean
  rebuild: zero warnings, `BUILD SUCCESSFUL`, all 243 tests green.
- Emulator, weather: set a real GPS fix (San Francisco) and got a live reading — ☀️ 14°C, matching
  actual conditions from met.no, not a stub. Tapping the chip launched the browser (Chrome's
  first-run screen came up rather than the final page, because this AVD had never opened Chrome
  before; the intent dispatch itself, which is what this feature is responsible for, succeeded).
- Emulator, address search: the search box renders correctly with its icon and updated hint text
  ("Drag the map … or search an address above"). **Not confirmed**: an actual completed search
  result. The emulator hit a genuine, unrelated resource problem partway through this check —
  `ActivityManager: ANR … Reason: Input dispatching timed out … Load: 15.46 / 14.35 / 9.13` — the
  *host* machine was overloaded from a very long session's worth of consecutive emulator work, not
  this feature: the same ANR reproduced immediately on a plain screen tap with no search involved,
  and the identical async pattern (`Dispatchers.IO` inside a `lifecycleScope.launch`) is exactly
  what the weather chip already proved works. `Geocoder.parse` itself is unit-tested against real
  Nominatim-shaped JSON. The honest gap is: nobody has watched a real device receive a real
  Nominatim response and move the crosshair yet.

## Not checked / known risks

- The address-search round trip end-to-end, per above — worth doing on a fresh emulator or the
  phone before calling this feature fully proven, not just "should work."
- Not tested on the Xiaomi (same local-build-vs-Play-signature limit as every build since 40).
- Nominatim and met.no are both free public services with no uptime guarantee; a rider without a
  connection, or hitting a slow response, sees "Couldn't search" / no weather chip respectively —
  by design, not a bug.

## Rolling back

`git revert <sha>`. Clean — the weather cache in `Prefs` is additive (new keys only) and a rollback
simply stops reading and writing them.

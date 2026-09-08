# Build 38 — the place picker names the street straight away

Date: 8 September 2026. Commit: `3b308eb`. Tag: `velotrack-v38`.

## What a rider notices

Opening **Choose on map** to set Home, Work or a favourite used to show *No name here* over a
perfectly well-named street until the map was dragged. Now the name appears as soon as the map has
drawn.

## Why

The picker names whatever sits under the fixed crosshair by querying the rendered vector tiles.
The first query ran in `getMapAsync`, before a single tile had been rendered, so it found nothing —
and a movement threshold (re-query only after the crosshair has moved 15 m, which stops the name
flickering while dragging) then suppressed every retry until the rider moved the map. The threshold
was right; the missing part was a reason to ask again when the *map* changed rather than the
camera.

## What changed

| File | Change |
|---|---|
| `ui/PlacePickerActivity.kt` | `refreshPlaceName` takes a `force` flag that bypasses the 15 m movement threshold, and is called from `mapView.addOnDidBecomeIdleListener` (tiles finished drawing) and again after `loadStyle`. |

## How it was checked

- Emulator (`Pixel_10a`): the picker opened on Paris tiles and showed the road name without any
  drag. Verified by screenshot.

## Not checked / known risks

- There is still no address search anywhere in the app, and that is deliberate: VeloTrack carries
  no geocoder and the Protomaps basemap has no house numbers, so the honest tool is the map plus
  the name under the crosshair. A rider asking "where do I type my address?" is asking for
  something the offline design cannot give; if that keeps coming up, the answer is a bundled
  geocoder, not a search box that fails.

## Rolling back

`git revert 3b308eb`. Clean.

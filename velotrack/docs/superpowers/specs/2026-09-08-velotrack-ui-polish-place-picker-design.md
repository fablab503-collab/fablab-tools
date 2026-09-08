# VeloTrack round 4: flat controls, a foldable HUD, and choosing a place on a map

Date: 2026-09-08. From a spoken brief after the first real installs. Five requests, four of them
small, one of them not.

## What was asked, and what it means

1. **"Underneath the icons there is a black shadow. Remove it completely."** The left-hand buttons
   are `Widget.Material3.FloatingActionButton.Surface`, which carries Material's default elevation,
   so each one drops a shadow onto the map. On the black night style that reads as a dirty smudge
   rather than depth.
2. **"Tap to take the top bar out and put it back."** The statistics card covers the top third of
   the map and cannot be dismissed.
3. **"When you click Home, Work or a favourite, a little map should come out so you can find the
   location precisely. I still cannot see anywhere to look up my home or the right address."**
   Today the only ways to place a favourite are the current GPS position or the centre of the main
   map, which means panning the main map first and guessing.
4. **"The downloaded maps say z13–15, Roads z10–12, strange names."** The download and Map data
   screens print zoom bands at the rider.
5. **"Make it more fluid while using the app."** Taken as motion: things appear and disappear
   abruptly.

## Decisions

**Flat controls.** One style, `Widget.VeloTrack.Fab.Surface`, with elevation and both translation-Z
values at zero, applied to every round button, the stop button and the two extended buttons. The
statistics card loses its elevation too and becomes a filled surface, because a flat button column
next to a floating card looks like an accident rather than a choice. Contrast now comes from the
surface colour alone, which is what the dark style wants.

**Foldable HUD.** Tapping the card folds it away upward, and a small chevron button appears where
it was; tapping the chevron brings it back. The choice is remembered in `Prefs.hudHidden`, so the
app opens the way it was left. Riding mode already fades the button column and is left alone: the
two are independent, and a rider who folded the HUD away wants it to stay away.

**Choosing a place on a map.** A new `PlacePickerActivity`: the offline map filling the screen, a
fixed crosshair at the centre, and a card that names whatever sits under the crosshair as you pan,
reusing `MapController.placeNameAt`. You drag the map until the name reads like your street, type a
label, and save. It reuses `MapController` so the picker draws with the same style, bands and
theme as the main map. Reachable from Home, from Work and from adding a favourite.

*What this deliberately is not:* a search box. There is no offline geocoder in the app and the
Protomaps basemap carries no house numbers, so no amount of work here produces "12 Rue de Rivoli".
Real search over town, village and street names is possible, but only by building a name index
while a map region downloads, which is its own piece of work. The crosshair plus the live street
name gives the precision that was actually missing; search is proposed separately.

**Plain names for map areas.** Zoom bands are an implementation detail. "10 km · streets · z13–15"
becomes "Nearby · 10 km · every street", "Roads · z10–12" becomes "Wider area · main roads", and
the Map data rows drop the zoom numbers entirely. The zoom range stays in the code and in the
README for anyone who cares.

**Motion.** The HUD fold is a 220 ms translate-and-fade rather than a visibility flip, and the
picker's name card cross-fades as the name under the crosshair changes.

## Contracts

- `res/values/styles.xml` (new): `Widget.VeloTrack.Fab.Surface`, `Widget.VeloTrack.Fab.Error`,
  `Widget.VeloTrack.Card.Flat`.
- `Prefs.hudHidden: Boolean` on key `hud_hidden`, default false.
- `PlacePickerActivity`: started with optional `EXTRA_LAT`, `EXTRA_LON`, `EXTRA_TITLE`,
  `EXTRA_NAME`; returns `RESULT_OK` with `EXTRA_LAT`, `EXTRA_LON`, `EXTRA_NAME`.
- `MainActivity`: `hudHiddenApply(animate: Boolean)`, `placePickerLauncher`, and a third
  "Choose on map" button on the Home/Work dialog and the favourite dialog.
- View ids added to `activity_main.xml`: `btnShowHud`.

## Acceptance

- No button in the app casts a shadow on the map, in either theme.
- Tapping the statistics card folds it away; the chevron brings it back; the state survives a
  restart.
- Home, Work and a new favourite can each be placed by dragging a map, and the card names the road
  under the crosshair as it moves.
- No screen shows a "z" followed by a number to the rider.
- Both themes still render, and riding mode still fades the buttons above 5 km/h.

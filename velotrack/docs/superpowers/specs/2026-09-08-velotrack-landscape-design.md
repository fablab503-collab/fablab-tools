# VeloTrack: landscape

Date: 2026-09-08. Reported after the first real-phone install: "in landscape mode it is not
working well."

## What is actually wrong

Seen on the emulator rotated to landscape:

- The six round buttons stack upward from the bottom-left and land on top of the statistics
  card, hiding the speed. The column is taller than the screen is high.
- The card spans the full width, so four statistics sit metres apart and the map is a strip along
  the bottom.
- Nothing re-lays out on rotation. Both map activities declare
  `configChanges="orientation|screenSize|keyboardHidden"`, so Android keeps the portrait view tree
  and just stretches it. A `layout-land` folder would never be read.
- The camera pads only the top of the map (55 % of its height) to keep the puck under the card.
  With the card moved to the side that padding is wrong and there is no side padding.
- Guidance to a favourite lives only in a field, so recreating the activity would drop it.

## Decisions

**Let Android rotate the activity.** Drop `orientation|screenSize` from `configChanges` on
`MainActivity` and `PlacePickerActivity`; keep `keyboardHidden`. The map view already saves and
restores its state, the recording lives in the service, the folded-card choice is in preferences,
and the app already recreates the main activity after a map download, so recreation is a known-safe
path. Cost: a second of map reload on rotation, which on a handlebar mount happens about never.

**A side panel, not a top bar.** `layout-land/activity_main.xml`: the statistics card becomes a
340 dp panel on the left, full height, with the speed at 56 sp, one line for clock / battery / GPS,
the four statistics in a 2 x 2 grid, and the place, guidance, route and error rows under them. The
six round buttons become 48 dp and run down the right edge, vertically centred. Record / Pause /
Stop sit at the bottom centre of the map area. The no-map card centres over the map area. Every
view id from the portrait layout exists in the landscape one and no new ids are added, because
ViewBinding makes an id that exists in only one variant nullable and the activity treats them all as
non-null.

**A corner card for the picker.** `layout-land/activity_place_picker.xml`: the hint, name-under-
crosshair and label field become a 320 dp card in the top-left; the crosshair stays at the true
centre of the screen, because Save reads the camera target and that is the screen centre.

**Pad the camera on the side too.** `MapController` gains `cameraPaddingLeftPx`; the activity sets
it to the panel's measured width in landscape and 0 otherwise, and the follow-mode camera keeps the
puck centred in the visible map rather than under the panel.

**Guidance survives rotation.** `MainActivity` saves the guided favourite's id in the instance
state and restarts guidance to it after the database is available.

**Insets on all four sides.** The panel takes the top and start insets, the controls take bottom and
end, so a cut-out or a side navigation bar in landscape does not eat a button.

## Acceptance

- Landscape: speed readable, no button over the card, map fills the right ~60 % with the puck
  centred in it, record button reachable, folding the card still works.
- Portrait unchanged.
- Rotate during guidance: the arrow row and the dashed line come back.
- Rotate during a recording: statistics keep counting.

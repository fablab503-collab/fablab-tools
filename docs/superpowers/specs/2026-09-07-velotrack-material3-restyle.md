# VeloTrack — Material 3 (Google style) restyle

Date: 2026-09-07. Brief from the user: "check the design again and make it more Android or Google
style." The direction is therefore fixed: **Material Design 3 / Material You**, as Google's own
Android apps (Maps, Fit, Recorder) look. Functionality does not change.

## 1. Critique of the current UI (emulator screenshots, build 1.0.7)

| Area | Problem | Material 3 answer |
|---|---|---|
| Colours | Ad-hoc hex values (`#B3000000` HUD, `#B0BEC5` labels, red/amber/grey squares) | Theme roles only: `colorSurface*`, `colorPrimary*`, `colorError*`, `colorOnSurfaceVariant`; Material You dynamic colour on Android 12+ |
| HUD | Translucent black slab, no shape, cramped stats | `MaterialCardView` (elevated, `colorSurfaceContainer`, 28dp corners), M3 type scale, 16dp grid |
| Controls | Mixed components: mini FAB, tonal pill, two custom coloured squares | Google Maps pattern: primary **Extended FAB** for the main action, standard FABs (56dp) on a right-hand stack, one small surface FAB for the menu |
| Status text | Raw red/amber text | M3 assist `Chip` with a leading icon, tinted by role (error = recording dot, tertiary = auto-paused) |
| Icons | Custom vectors of mixed weight | Material Symbols Rounded, 24dp, one family |
| Lists | Bare `ListView` rows with a divider, wide custom pill button | M3 two-line list items (leading 40dp icon, `bodyLarge` + `bodyMedium`), 72dp min height, Extended FAB bottom-right for the primary action |
| Banners / overlays | Custom orange and black boxes | `MaterialCardView` in `colorErrorContainer` (GPS off) and `colorSurfaceContainerHigh` (no map) with M3 buttons |
| Launcher icon | White arrow on black | Adaptive icon with a `colorPrimary` background, bike glyph foreground, and a `<monochrome>` layer for themed icons |
| Typography | Sizes chosen per view | Roboto via M3 text appearances: `displayLarge` (speed), `titleLarge` (stat values), `labelMedium` (stat labels), `bodyMedium` |

What stays: dark theme only (OLED battery is a product requirement), pure-black map earth, the
information hierarchy (speed first, then distance / moving time / average / climb), all view ids
used by the Kotlin code unless listed below.

## 2. Tokens

- Theme: `Theme.Material3.Dark.NoActionBar` + `DynamicColors.applyToActivitiesIfAvailable(app)` in
  `VeloTrackApp`. Baseline (pre-Android-12 or dynamic colour unavailable): seed the M3 palette from
  Google blue `#4285F4` (`colorPrimary` ≈ `#A8C7FA`, `colorPrimaryContainer` ≈ `#0842A0`,
  `colorOnPrimaryContainer` ≈ `#D3E3FD`, `colorSurface` `#131314`, `colorSurfaceContainer`
  `#1E1F20`, `colorSurfaceContainerHigh` `#282A2C`, `colorOnSurface` `#E3E3E3`,
  `colorOnSurfaceVariant` `#C4C7C5`, `colorError` `#F2B8B5`, `colorErrorContainer` `#8C1D18`,
  `colorTertiary` `#FFB68F` or the Material default). Window background `?attr/colorSurface`.
  Status and navigation bars transparent (edge to edge is already handled by insets).
- Shape: cards 28dp (`ShapeAppearance.Material3.Corner.ExtraLarge`), FABs default M3 shapes.
- Spacing: 16dp screen margin, 8dp between FABs, 12dp card inner padding, 4dp between value and label.
- Type: speed `textAppearanceDisplayLarge` at 64sp `sans-serif-medium`; unit `titleMedium`;
  clock `titleLarge`; stat values `titleLarge`; stat labels `labelMedium` in `colorOnSurfaceVariant`;
  chips `labelLarge`. Sentence case everywhere, no all-caps.
- Map colours passed from the theme at runtime: track = `colorPrimary`, route = `colorTertiary`,
  puck = `colorPrimary` disc with `colorOnPrimary` chevron and a 20 % `colorPrimary` halo.

## 3. Layout (main screen)

```
┌──────────────────────────────────────────┐  status bar (transparent)
│ ┌──────────────────────────────────────┐ │
│ │ 18.9  km/h              6:35 PM      │ │  HUD card, colorSurfaceContainer, 28dp
│ │                         100% · GPS 6 │ │
│ │ [● Recording]                        │ │  assist chip (only while recording/paused)
│ │ 456 m   1:00:12   26.9 km/h   12 m   │ │  titleLarge values
│ │ Distance Moving   Avg        Climb   │ │  labelMedium, onSurfaceVariant
│ └──────────────────────────────────────┘ │
│                                          │
│                 (map)                    │
│                                    ┌──┐  │
│                                    │⊕ │  │  FAB recenter (my_location), surface
│                                    └──┘  │
│                                    ┌──┐  │
│                                    │3D│  │  FAB view toggle (3d_rotation / map)
│ ┌──┐                               └──┘  │
│ │⋮ │      [ ●  Record ]                  │  small surface FAB menu; Extended FAB primary
│ └──┘  while recording: [ ❚❚ Pause ] [■]  │  Extended FAB tonal + FAB stop (errorContainer)
└──────────────────────────────────────────┘  nav bar (transparent)
```

Alignment: HUD content left-aligned with the clock block right-aligned; FAB stack right-aligned,
16dp from the edge and 16dp above the primary action row; primary action centred.

## 4. Components and view ids (binding contract for the Kotlin code)

`activity_main.xml` keeps these ids with the class in brackets: `mapContainer` (FrameLayout),
`overlay` (FrameLayout), `topPanel` (LinearLayout), `hudPanel` (**MaterialCardView**), `speedText`,
`speedUnit`, `clockText`, `batteryText`, `gpsText`, `statusText` (**Chip**, non-clickable,
`Widget.Material3.Chip.Assist`, `chipIcon` set from code), `distanceText`, `movingTimeText`,
`avgSpeedText`, `elevationText`, `routeText`, `errorText`, `gpsBanner` (**MaterialCardView**),
`btnGpsSettings` (MaterialButton), `noMapOverlay` (**MaterialCardView** centred), `btnMapFiles`
(MaterialButton filled), `controls` (a ConstraintLayout or FrameLayout at the bottom that holds:)
`btnMenu` (**FloatingActionButton**, small, surface), `btnToggle3d` (**FloatingActionButton**,
standard, surface; icon `ic_3d_rotation` when in 2D mode → tapping goes to 3D, `ic_map` when in 3D),
`btnRecenter` (**FloatingActionButton**, standard, surface, `ic_my_location`), `btnRecord`
(**ExtendedFloatingActionButton**, primary, `ic_record`, text "Record"), `btnPause`
(**ExtendedFloatingActionButton**, tonal/secondary container, `ic_pause` / `ic_play`, text
"Pause" / "Resume"), `btnStop` (**FloatingActionButton**, `backgroundTint ?attr/colorErrorContainer`,
`tint ?attr/colorOnErrorContainer`, `ic_stop`). Every FAB has a `contentDescription`.

Lists: `item_list_row.xml` → M3 two-line item: `leadingIcon` (ImageView 24dp inside a 40dp circle
`colorSecondaryContainer`), `title` (`bodyLarge`), `subtitle` (`bodyMedium`, `onSurfaceVariant`),
`trailingIcon` (ImageView `ic_more_vert` or `ic_check` for the active map). `activity_tracks.xml`
and `activity_map_files.xml`: `MaterialToolbar` (surface, `titleLarge`), the ListView, and for map
files an `ExtendedFloatingActionButton` id `btnImport` bottom-right (`ic_add`, "Import") replacing the
wide button. Empty states: a centred `bodyLarge` text + hint (`onSurfaceVariant`).

Icons (Material Symbols Rounded, 24dp, from google/material-design-icons `symbols/android/<name>/materialsymbolsrounded/<name>_24px.xml`,
saved as `res/drawable/ic_<name>.xml` with `android:tint="?attr/colorControlNormal"` removed so the
component tints them): `fiber_manual_record`→`ic_record`, `pause`→`ic_pause`, `play_arrow`→`ic_play`,
`stop`→`ic_stop`, `my_location`→`ic_my_location`, `3d_rotation`→`ic_3d_rotation`, `map`→`ic_map`,
`more_vert`→`ic_more_vert`, `add`→`ic_add`, `directions_bike`→`ic_directions_bike`, `route`→`ic_route`,
`location_off`→`ic_location_off`, `satellite_alt`→`ic_satellite`, `check`→`ic_check`,
`pause_circle`→`ic_pause_circle` (auto-paused chip), `radio_button_checked`→`ic_recording_dot` (chip).
Notification icon `ic_stat_rec` stays (white glyph on transparent is required there).

Launcher: `mipmap-anydpi-v26/ic_launcher.xml` with `<background android:drawable="@color/ic_launcher_bg"/>`
(`#4285F4`), `<foreground android:drawable="@drawable/ic_launcher_foreground"/>` (bike glyph, white,
centred in the 66dp safe zone of the 108dp canvas), `<monochrome android:drawable="@drawable/ic_launcher_foreground"/>`.

## 5. Kotlin changes

- `VeloTrackApp.onCreate`: `DynamicColors.applyToActivitiesIfAvailable(this)` after `MapLibre.getInstance`.
- `MainActivity`: adapt to the new classes (`ExtendedFloatingActionButton.text/setIconResource`,
  `FloatingActionButton.setImageResource`, `Chip.text/chipIcon/isVisible`); resolve theme colours with
  `MaterialColors.getColor(view, com.google.android.material.R.attr.colorPrimary)` and pass them to
  `MapController.setThemeColors(trackColor, routeColor, puckColor, puckOnColor)` once the map is ready;
  status chip text/icon: Recording → `ic_recording_dot` tinted `colorError`; Auto-paused →
  `ic_pause_circle` tinted `colorTertiary`; Paused → `ic_pause`; idle → chip hidden.
- `MapController`: new public `fun setThemeColors(trackColor: Int, routeColor: Int, puckColor: Int, puckOnColor: Int)`
  (defaults remain the current constants) applied to the line layers and the puck bitmap; puck drawn as
  a 22dp disc in `puckColor` with a 2dp `puckOnColor` ring and a `puckOnColor` chevron, plus a soft
  halo (`puckColor` at 20 % alpha, 40dp).
- `TracksActivity` / `MapFilesActivity`: adapter binds the new row ids; map files show `ic_check` on the
  active file and `ic_map` leading icon; tracks show `ic_directions_bike`; import via `btnImport`.
- Strings: add `btn_record`, `btn_pause`, `btn_resume`, `btn_stop`, content descriptions for every FAB,
  and rename any all-caps label to sentence case.

## 6. Acceptance (emulator)

Screenshots of: idle map, recording (chip + pause/stop), auto-paused, tracks list, map files, settings,
about. Checks: all text on cards ≥ 4.5:1 contrast, touch targets ≥ 48dp, no hard-coded colours in
layouts except the launcher background, dynamic colour visibly applied on the API 37 emulator, nothing
under the status or navigation bars.

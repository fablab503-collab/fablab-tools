# VeloTrack round 3: riding mode, screen & theme settings, Interstate font, 3D icons, auto-record + totals

Date: 2026-09-07. Brief for the parallel implementation workflow. Every signature, id and resource
name below is binding; agents implement against it without a compiler (CI compiles).

## 0. User request (verbatim intent)

- Above 5 km/h the screen becomes minimalist: every button disappears, only the top (HUD) stays.
  Below 5 km/h, or when the screen is touched, the buttons come back.
- Option to keep the screen from turning off while a session (recording) runs.
- Option to switch dark / light automatically by the hour of the day.
- Use the Interstate font for the whole app.
- Add the 3D icon set where the app can use it.
- Always leave a trace of a session in the data so the app can tell how many km were ridden:
  auto-start recording when riding, and a statistics screen with totals.

## 1. Decisions (and why)

| Topic | Decision |
|---|---|
| Riding mode | `RidingModeController` (pure Kotlin, JVM-tested) hides the whole `controls` container (both FAB column and action row) when the effective speed is ≥ 5 km/h (1.389 m/s) for 3 s; shows it again when speed < 5 km/h for 2 s, or on any touch (then re-hides after 8 s while still ≥ 5 km/h). The HUD, GPS banner and the no-map overlay always stay. Fade 200 ms, `GONE` afterwards so map gestures pass through. |
| Speed source | While recording: `RideState.speedMps`; idle: the local fix `speedMps` (emulator reports 0 → riding mode simply does not trigger idle). |
| Keep screen on | Existing `screen_mode` (KEEP_ON / DIM / SYSTEM) now applies **only while a recording is active**; when idle the system setting rules (battery). Title changes to "Screen during a ride". |
| Theme | New `theme_mode` (`ThemeMode.DARK` / `LIGHT` / `AUTO`, default `AUTO`). AUTO = dark between sunset and sunrise computed from the last known position (fallback 07:00–19:00 local when no position). Applied with `AppCompatDelegate.setDefaultNightMode` in `VeloTrackApp.onCreate` and re-evaluated by `NightModeManager` when MainActivity resumes and at the next transition (+1 min) while it is resumed. Theme becomes `Theme.Material3.DayNight.NoActionBar`; light palette in `values/colors.xml`, dark palette in `values-night/colors.xml`. Map style follows: `style_light.json` (Protomaps light flavour) in light, `style.json` (dark, black earth) in dark. |
| Font | `res/font/interstate_comp.otf` (Interstate Comp Regular, already copied) wrapped by `res/font/interstate.xml`; applied to the theme's `android:fontFamily` and to all 15 Material 3 text appearances via `values/type.xml`. Single weight: bold falls back to synthetic bold. |
| 3D icons | Full set (105 icons, 320 px WebP, "color" style) lives in the repo at `assets/icons3d/` for future use. The app ships a subset in `res/drawable-nodpi/img3d_*.webp` (already copied): bag, boy, calender, chart, clock, explorer, fire, flag, flash, map_pin, medal, mobile, rocket, sphere, star, target, tick, travel, trophy. Used as illustrations only (never as 24 dp glyphs): statistics cards, empty states, no-map overlay. |
| Auto-record | `AutoRecordDetector` (pure Kotlin, JVM-tested): while idle and `auto_record` (default on) is enabled, ≥ 3 fixes with speed ≥ 5 km/h spanning ≥ 10 s and accuracy ≤ cutoff → `MainActivity.startRecording()` + toast "Recording started automatically". 2-minute cooldown after any stop. |
| Totals | `StatsRepository.totals(sinceMs)` aggregates finished tracks; `StatsActivity` shows All time / This year / This month / This week cards (distance, rides, moving time, climb, longest ride). Menu item "Statistics" in the main menu. Empty state with the rocket icon. |

## 2. Module ownership (an agent edits only its files)

| Module | Files |
|---|---|
| A-resources | `res/font/interstate.xml` (new), `res/values/type.xml` (new), `res/values/themes.xml`, `res/values/colors.xml` (becomes LIGHT), `res/values-night/colors.xml` (new, DARK = current values), `res/values-night/themes.xml` (new), `res/values/strings.xml` (ALL new strings of this round, list in §3.1), `res/values/arrays.xml`, `res/xml/preferences.xml`, `res/layout/sheet_favorites.xml` (empty-state icon), `res/layout/activity_tracks.xml` (empty-state icon) |
| B-theme-settings | `model/ThemeMode.kt` (new), `settings/Prefs.kt` (add `themeMode`, `autoRecord`), `geo/SolarTimes.kt` (new) + `test/.../geo/SolarTimesTest.kt`, `ui/NightModeManager.kt` (new), `VeloTrackApp.kt` |
| C-main | `ui/RidingModeController.kt` (new) + `test/.../ui/RidingModeControllerTest.kt`, `ui/AutoRecordDetector.kt` (new) + `test/.../ui/AutoRecordDetectorTest.kt`, `ui/MainActivity.kt`, `res/layout/activity_main.xml` (no-map overlay icon only), `res/menu/main_menu.xml` |
| D-stats | `model/RideTotals.kt` (new), `storage/StatsRepository.kt` (new), `ui/StatsActivity.kt` (new), `res/layout/activity_stats.xml` (new), `res/layout/item_stats_card.xml` (new), `AndroidManifest.xml` (register `.ui.StatsActivity`) |
| E-map | `tools/gen-style.mjs`, `tools/build-assets.sh`, `velotrack/.gitignore`, `map/MapController.kt` (`lightMap`, light empty style); `test/.../map/StyleTemplateTest.kt` untouched |

Shared files not listed above must not be edited. `Models.kt` is not edited (new enums/classes go in new files).

## 3. Contracts

### 3.1 Strings (module A writes them; everyone references them)

```
pref_cat_appearance            Appearance
pref_theme_title               Theme
theme_mode_dark                Dark
theme_mode_light               Light
theme_mode_auto                Auto (dark after sunset)
pref_screen_mode_title         Screen during a ride            (REPLACES the current value)
pref_screen_mode_summary       Applies while a ride is being recorded
pref_auto_record_title         Start recording automatically
pref_auto_record_summary       When you ride faster than 5 km/h for 10 seconds
auto_record_started            Recording started automatically
menu_stats                     Statistics
title_stats                    Statistics
stats_all_time                 All time
stats_this_year                This year
stats_this_month               This month
stats_this_week                This week
stats_rides                    %1$d rides                      (plural handled as "1 ride" via stats_ride_one)
stats_ride_one                 1 ride
stats_moving                   Moving %1$s
stats_climb                    Climb %1$s
stats_longest                  Longest ride %1$s
stats_empty_title              No rides yet
stats_empty_body               Start a ride and your kilometres add up here.
stats_total_headline           %1$s ridden in total
cd_stats_icon                  Statistics illustration
```
Existing strings stay. `arrays.xml` gains `theme_mode_entries` (@string/theme_mode_dark, _light, _auto) and `theme_mode_values` (DARK, LIGHT, AUTO). `preferences.xml`: new category `pref_cat_appearance` FIRST with `ListPreference key="theme_mode" defaultValue="AUTO"`; in `pref_cat_display` the `screen_mode` preference gets `android:summary="@string/pref_screen_mode_summary"` instead of the simple summary provider… keep `useSimpleSummaryProvider` and drop the summary attribute (title already says "during a ride"); in `pref_cat_recording` add `SwitchPreferenceCompat key="auto_record" defaultValue="true"` after `auto_pause`.

### 3.2 Font and type (module A)

- Verified (research, `/tmp/claude-501/research3/android-font-theme.md`): with minSdk 26 the `android:` namespace alone is enough in font XML; Material's own M3 TextAppearance styles set both `fontFamily` and `android:fontFamily`, and the Material TextAppearance parser reads either; a theme-level `android:fontFamily` reaches every TextView-based widget (buttons, dialog titles, toolbar, preference titles, text-field labels), while drawable-rendered text (Chip, Slider tooltip, Badge) only honours the `textAppearance*` attributes — so both are needed. Preference titles use `?android:attr/textAppearanceListItem`.
- `res/font/interstate.xml` (the OTF is already at `res/font/interstate_comp.otf`):
  ```xml
  <font-family xmlns:android="http://schemas.android.com/apk/res/android">
      <font android:font="@font/interstate_comp" android:fontStyle="normal" android:fontWeight="400" />
  </font-family>
  ```
- `res/values/type.xml`: 15 styles `TextAppearance.VeloTrack.<Role>` with parent `TextAppearance.Material3.<Role>` for DisplayLarge, DisplayMedium, DisplaySmall, HeadlineLarge, HeadlineMedium, HeadlineSmall, TitleLarge, TitleMedium, TitleSmall, BodyLarge, BodyMedium, BodySmall, LabelLarge, LabelMedium, LabelSmall; each sets `<item name="fontFamily">@font/interstate</item>` and `<item name="android:fontFamily">@font/interstate</item>` and `<item name="android:letterSpacing">0</item>` (condensed face). Plus `TextAppearance.VeloTrack.ListItem` (parent `TextAppearance.AppCompat.Subhead`, same three items) for `android:textAppearanceListItem`.
- `themes.xml`: parent `Theme.Material3.DayNight.NoActionBar`; add `<item name="android:fontFamily">@font/interstate</item>`, `<item name="fontFamily">@font/interstate</item>`, `<item name="android:textAppearanceListItem">@style/TextAppearance.VeloTrack.ListItem</item>` and the 15 `textAppearance<Role>` items pointing at the styles above; keep the colour role items (they now resolve to light or dark via the two colors.xml files); `android:windowLightStatusBar` → `true` in `values/themes.xml` and `false` in a `values-night/themes.xml` that redefines `Theme.VeloTrack` fully (identical copy except that item). Do not add `configChanges="uiMode"` anywhere: AppCompat must recreate activities on a theme change.
- Light palette (`values/colors.xml`, Material 3 tones seeded from Google blue): md_primary #FF0B57D0, md_on_primary #FFFFFFFF, md_primary_container #FFD3E3FD, md_on_primary_container #FF041E49, md_secondary_container #FFDAE2F9, md_on_secondary_container #FF131C2B, md_tertiary #FF8B5000, md_surface #FFF9F9FF, md_surface_container #FFEDEEF4, md_surface_container_high #FFE7E8EE, md_on_surface #FF191C20, md_on_surface_variant #FF44474E, md_error #FFBA1A1A, md_error_container #FFFFDAD6, md_outline #FF74777F, md_outline_variant #FFC4C6D0. Dark palette = the current `colors.xml` values moved to `values-night/colors.xml`. `colors_launcher.xml` untouched.
- Empty-state icons: `activity_tracks.xml` gets an `ImageView` (id `emptyImage`, 96 dp, `@drawable/img3d_flag`, `contentDescription="@null"`, visibility follows `emptyText` — TracksActivity is NOT edited, so put the ImageView inside the same parent as `emptyText` and give it `android:visibility="gone"`; module A must keep it visually correct without code: use the existing empty-state container if `emptyText` has one and set the image `visibility` to match via… → Decision: TracksActivity is owned by nobody this round; therefore the ImageView must be a child of the same container that already toggles. If `emptyText`/`emptyHint` are toggled individually (they are), make the image part of a new `LinearLayout id=emptyGroup` wrapping `emptyImage`, `emptyText`, `emptyHint` and let it stay always-visible-layout-wise: acceptable because the list covers it when non-empty? No — instead give the image `android:visibility="gone"` and accept that only the texts show until a later round. (Module A: add the ImageView with `visibility="gone"`, ready for the next round.) Same for `sheet_favorites.xml` (`img3d_star`, gone).

### 3.3 Theme mode (module B)

```kotlin
// model/ThemeMode.kt
package com.fablab503.velotrack.model
enum class ThemeMode { DARK, LIGHT, AUTO }

// settings/Prefs.kt additions
var themeMode: ThemeMode            // key "theme_mode", default AUTO, stored as enum name
var autoRecord: Boolean             // key "auto_record", default true
const val KEY_THEME_MODE = "theme_mode"; const val KEY_AUTO_RECORD = "auto_record"

// geo/SolarTimes.kt — NOAA-style approximation, no external libs
object SolarTimes {
    /** Sunrise and sunset as epoch millis for the civil day containing [dayEpochMs] in [zone]; null above the polar circle when the sun does not rise/set. */
    fun sunriseSunset(lat: Double, lon: Double, dayEpochMs: Long, zone: java.time.ZoneId): Pair<Long, Long>?
    /** True when [nowMs] is before sunrise or after sunset at (lat, lon); [fallbackDayStartHour]=7, [fallbackDayEndHour]=19 when no position (lat/lon null). */
    fun isNight(lat: Double?, lon: Double?, nowMs: Long, zone: java.time.ZoneId): Boolean
    /** Next epoch millis at which isNight flips (sunrise or sunset, or the fallback hours), used to schedule a re-check. */
    fun nextTransitionMs(lat: Double?, lon: Double?, nowMs: Long, zone: java.time.ZoneId): Long
}

// ui/NightModeManager.kt
class NightModeManager(private val prefs: Prefs) {
    /** Applies AppCompatDelegate.setDefaultNightMode for the current ThemeMode and time. Returns the mode applied (MODE_NIGHT_YES/NO). Safe to call repeatedly; no-op when unchanged. */
    fun apply(nowMs: Long = System.currentTimeMillis()): Int
    /** Millis until the next re-evaluation is needed, or null for DARK/LIGHT. */
    fun millisUntilNextCheck(nowMs: Long = System.currentTimeMillis()): Long?
    /** True if the resolved mode for [nowMs] differs from what apply() last set. */
    fun needsChange(nowMs: Long = System.currentTimeMillis()): Boolean
}
```
`VeloTrackApp.onCreate`: `NightModeManager(Prefs(this)).apply()` BEFORE `DynamicColors.applyToActivitiesIfAvailable(this)`. Tests: `SolarTimesTest` — equator at lon 0 on 2026-03-20 gives sunrise within 06:00±15 min UTC and sunset within 18:00±15 min UTC; lat 80 on 2026-06-21 returns null; `isNight` fallback: 03:00 → true, 12:00 → false; `nextTransitionMs` > nowMs.

### 3.4 Riding mode + auto record (module C)

```kotlin
// ui/RidingModeController.kt — no Android imports
class RidingModeController(
    private val enterSpeedMps: Float = 5f / 3.6f, private val enterAfterMs: Long = 3_000, private val exitAfterMs: Long = 2_000,
    private val touchShowMs: Long = 8_000,
) {
    /** True when the controls should be hidden. */
    var hidden: Boolean = false; private set
    /** Feed the effective speed (null = unknown → treated as 0) with a monotonic clock; returns true when [hidden] changed. */
    fun onSpeed(speedMps: Float?, nowMs: Long): Boolean
    /** Any touch: show controls; they hide again after touchShowMs while still fast. Returns true when [hidden] changed. */
    fun onTouch(nowMs: Long): Boolean
    /** Recording stopped / activity paused: show controls and reset timers. Returns true when [hidden] changed. */
    fun reset(): Boolean
    /** Millis until the state may change without new input (touch timeout), or null. */
    fun nextCheckDelayMs(nowMs: Long): Long?
}

// ui/AutoRecordDetector.kt — no Android imports
class AutoRecordDetector(private val minSpeedMps: Float = 5f / 3.6f, private val minDurationMs: Long = 10_000, private val minFixes: Int = 3, private val cooldownMs: Long = 120_000) {
    /** Returns true exactly once when riding is detected; call [reset] after acting. speed null or accuracy > accuracyCutoffM count as slow. */
    fun onFix(speedMps: Float?, accuracyM: Float, accuracyCutoffM: Float, nowMs: Long): Boolean
    fun reset()
    /** Suppress detection until nowMs + cooldownMs (call when a recording stops). */
    fun cooldown(nowMs: Long)
}
```
MainActivity wiring (module C):
- Touch detection: `override fun onUserInteraction() { super.onUserInteraction(); onScreenTouched() }` — verified: Activity calls it on every ACTION_DOWN before window dispatch, so map gestures are untouched. Do NOT put an OnTouchListener on the MapView and never return true from dispatchTouchEvent.
- Per fix (both idle `onFix` and recording render paths, right after `updatePlace`): `ridingMode.onSpeed(speed, SystemClock.elapsedRealtime())` and `applyRidingMode()` if changed. `applyRidingMode()` fades `binding.controls`: hide = `controls.animate().cancel(); controls.animate().alpha(0f).setDuration(200).withEndAction { controls.isVisible = false; controls.alpha = 1f }`; show = `controls.animate().cancel(); controls.alpha = 0f; controls.isVisible = true; controls.animate().alpha(1f).setDuration(200)` (verified: a cancelled fade-out never runs its end action, and alpha-0 VISIBLE views still eat taps, hence GONE + alpha reset). A `Handler` runnable re-checks after `nextCheckDelayMs`. `reset()` on `onPause`, when recording stops, and when a dialog/sheet opens (Home/Work/Favourites/menu taps count as touches anyway).
- Idle path: `autoRecord.onFix(fix.speedMps, fix.accuracyM, prefs.accuracyCutoffM, now)`; when true and `prefs.autoRecord` and status IDLE and `hasFineLocation()`: `Toast(auto_record_started)` + `startRecording()`; call `autoRecord.cooldown(now)` when a recording stops (status transitions to IDLE from non-IDLE) and after the auto start.
- Screen mode: `applyScreenMode()` now takes the recording status into account: KEEP_ON/DIM only when `RideSession.state.value.status != IDLE`; otherwise behave as SYSTEM. Call it on every status change (where the record/pause/stop buttons are rendered) and on resume (verified: window flags die with a recreated Activity, so derive them from state, never only from a click handler; `window.attributes` must be copied, modified and set back).
- Auto-start legality (verified): starting the location foreground service from a resumed Activity is a foreground start on Android 12–15 and needs no user tap; keep the existing `launchService { RideController.startNew(this) }` path and wrap in the existing error handling.
- Night mode: `nightMode = NightModeManager(prefs)`; in `onResume` if `nightMode.needsChange()` → `nightMode.apply()` (AppCompat recreates the activity; return early). Schedule a `Handler` runnable at `millisUntilNextCheck() + 60_000` that calls the same check; cancel in `onPause`. Prefs listener: when `theme_mode` changes → `nightMode.apply()`.
- Light map: before `prepareMapData()` (and in `reloadStyle()`), set `mapController.lightMap = !isNightUi()` where `isNightUi() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES`.
- Menu: `main_menu.xml` gets `<item android:id="@+id/action_stats" android:title="@string/menu_stats" />` between Tracks and Import route; handler `startActivity(Intent(this, StatsActivity::class.java))`.
- `activity_main.xml`: inside the `noMapOverlay` inner LinearLayout add an `ImageView` (id `noMapImage`, 96 dp, `@drawable/img3d_explorer`, `contentDescription="@null"`, `layout_marginBottom=16dp`) above its title text. Nothing else in the layout changes.
- Tests: `RidingModeControllerTest` (hidden after 3 s at 6 km/h; not hidden at 4 km/h; touch shows and re-hides after 8 s while fast; slow for 2 s shows), `AutoRecordDetectorTest` (3 fast fixes over 10 s → true once; slow fix resets; cooldown blocks).

### 3.5 Statistics (module D)

```kotlin
// model/RideTotals.kt
data class RideTotals(val rides: Int, val distanceM: Double, val movingMs: Long, val elevationGainM: Double, val longestDistanceM: Double, val maxSpeedMps: Double)

// storage/StatsRepository.kt
class StatsRepository(private val db: TrackDatabase) {
    /** Finished tracks (state = 'finished') with started_at >= sinceMs (null = all time). */
    fun totals(sinceMs: Long?): RideTotals
    companion object {
        /** Start of the local day/week (per Locale.getDefault() WeekFields)/month/year containing nowMs. */
        fun startOfWeekMs(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long
        fun startOfMonthMs(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long
        fun startOfYearMs(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long
    }
}
```
SQL (verified): `SELECT COUNT(*), COALESCE(SUM(distance_m),0), COALESCE(SUM(moving_ms),0), COALESCE(SUM(elevation_gain_m),0), COALESCE(MAX(distance_m),0), COALESCE(MAX(max_speed_mps),0) FROM tracks WHERE state = 'finished' AND started_at >= ?` (omit the `started_at` clause for all time). Period starts: `LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(WeekFields.of(Locale.getDefault()).firstDayOfWeek) | firstDayOfMonth() | firstDayOfYear()).atStartOfDay(zone).toInstant().toEpochMilli()`; the functions take `nowMs` so they are unit-testable (`StatsRepositoryTest` for the three start-of functions with a fixed zone `Europe/Paris` and Locale.FRANCE → Monday).

`StatsActivity` (Material toolbar like TracksActivity, `title_stats`): headline `stats_total_headline` with `Format.distance(all.distanceM, units)`; four cards (`item_stats_card.xml`: ImageView 56 dp start, title, distance headline, secondary lines rides / moving / climb / longest) with icons trophy (all time), calender (year), fire (month), flash (week); when `all.rides == 0` show the empty state (rocket 120 dp, `stats_empty_title`, `stats_empty_body`) instead of the cards. All DB work on `Dispatchers.IO`. Registered in the manifest as `.ui.StatsActivity`, `exported=false`, `label=@string/title_stats`. Uses `Format.distance/duration/elevation` and `prefs.units`.

### 3.6 Light map style (module E)

- Verified (research 2026-09-07, `/tmp/claude-501/research3/protomaps-light.md`): `namedFlavor()` in @protomaps/basemaps 5.7.2 accepts exactly `light`, `dark`, `white`, `grayscale`, `black`; `layers(source, flavor, { lang })` unchanged and labels exist only when `lang` is passed; each flavour has its own sprite sheet `sprites/v4/<flavor>{,@2x}.{json,png}`. Stock LIGHT casings (#e0e0e0 on earth #e2dfda) and label greys are too faint in sunlight.
- `gen-style.mjs`: CLI `node gen-style.mjs <outStyle> [--flavor dark|light]` (default dark). Dark keeps the current black patch and name `velotrack-dark`. Light = `namedFlavor("light")` patched by spread for sunlight legibility: `minor_casing` #b8b8b8, `major_casing_early`/`major_casing_late` #9a9a9a, `highway_casing_early`/`highway_casing_late` #8a8a8a, `bridges_*_casing` matching, `highway` #fff2c2, `major` #fffbe6, `roads_label_minor` #4a4a4a, `roads_label_major` #2e2e2e, `city_label` #1f1f1f, `subplace_label` #4a4a4a, `state_label` #7a7a7a, `country_label` #5c5c5c, `address_label` #4a4a4a (halos untouched). Name `velotrack-light`; `sprite: asset://sprites/v4/light`; POI layers removed exactly as for dark (same filter); the same four-band source/layer duplication applies. `vector_layers.json` written in both runs (same content).
- `build-assets.sh`: `SPRITES` gains `light.json light.png light@2x.json light@2x.png`; after the dark generation add `node gen-style.mjs "$ASSETS_DIR/style_light.json" --flavor light`; include `style_light.json` in the final existence check and the `du` line. `velotrack/.gitignore`: add `app/src/main/assets/style_light.json` next to `style.json`.
- `MapController`: `var lightMap: Boolean = false` (read at `loadStyle` time: template asset `style_light.json` when true, else `style.json`); `EMPTY_STYLE_JSON` becomes a function `emptyStyleJson()` returning a background-only style with `#F2F2F2` when `lightMap` else `#000000`; the reload pass-through uses the same function. Public API otherwise unchanged (`loadStyle(bandFiles, onDone)` signature stays). `StyleTemplateTest` must keep passing (template placeholders unchanged).

## 4. Acceptance (emulator)

1. Fresh install shows Interstate in the HUD, menus, dialogs and settings.
2. Settings → Appearance → Theme: Light turns the whole UI and the map light; Dark back; Auto matches the hour.
3. Start a recording and simulate 6 km/h for 3 s: all buttons fade out, HUD stays; tap the map: buttons return and fade again after 8 s; slow down to 3 km/h: buttons return.
4. With auto-record on and idle, simulate 6 km/h for 10 s: recording starts by itself with a toast.
5. Statistics shows totals matching the Tracks list; empty state when no rides.
6. Screen stays on only while recording (KEEP_ON default); `adb shell dumpsys window | grep KEEP_SCREEN_ON`.

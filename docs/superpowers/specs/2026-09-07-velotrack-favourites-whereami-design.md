# VeloTrack — Favourites (Home, Work, custom places) and "Where am I" line

Date: 2026-09-07. User brief: "add home address on the left side as a favourite, work, and a
multi-icon site for favourites (one icon that pops up other places like mom and dad, sister,
brother, girlfriend, favourite restaurant, favourite theatre …), letting people add their own
description and add or delete as many as they want. Then show exactly under the speed / distance /
average also the exact street, park or way where I am."

## 1. Decisions

| Decision | Why |
|---|---|
| A favourite is a saved place: name, description, kind, lat/lon. Home and Work are two fixed favourites with their own buttons; every other favourite lives in the Favourites sheet | Exactly the brief. Kinds give each place an icon: `home`, `work`, `person`, `restaurant`, `theatre`, `place` (generic). |
| Tapping a favourite starts **guidance**: a marker on the map, a dashed straight line from the rider to the place, and a HUD line "→ Home · 3.2 km · ahead-left" | The app has no routing engine; straight-line distance and relative direction is what a bike compass gives and is honest about it. |
| Places are set from "my position" or the "map centre" (pan the map to the spot first) | No geocoder offline; both options are already used by Download map. |
| "Where am I" comes from the map tiles themselves via `queryRenderedFeatures` around the puck | Works fully offline; the tiles carry road names (`name`) and park names. Priority: named road → named park/green area → named water → locality (town). |
| Left column order top→bottom: Home, Work, Favourites, Recenter, 2D/3D, Menu; all 56 dp surface FABs, 8 dp gaps | The brief asks for Home/Work/Favourites on the left; six buttons fit in 376 dp. |

## 2. Data

`favorites` table (TrackDatabase **version 3**, `onUpgrade` oldVersion < 3 creates it):
`id INTEGER PK AUTOINCREMENT, name TEXT NOT NULL, description TEXT NOT NULL DEFAULT '', kind TEXT NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, created_at INTEGER NOT NULL`.
Kinds: `home`, `work`, `person`, `restaurant`, `theatre`, `place`. At most one row each for `home` and `work` (upsert by kind).

Model (`model/Favorite.kt`): `data class Favorite(val id: Long, val name: String, val description: String, val kind: FavoriteKind, val lat: Double, val lon: Double, val createdAt: Long)`; `enum class FavoriteKind(val key: String) { HOME("home"), WORK("work"), PERSON("person"), RESTAURANT("restaurant"), THEATRE("theatre"), PLACE("place"); companion object { fun fromKey(key: String): FavoriteKind } }`.

## 3. Contracts

### 3.1 storage — `storage/FavoritesRepository.kt`
```kotlin
class FavoritesRepository(private val db: TrackDatabase) {
    fun list(): List<Favorite>                                   // home, work first, then by name
    fun get(id: Long): Favorite?
    fun getByKind(kind: FavoriteKind): Favorite?                  // for HOME / WORK
    fun upsertFixed(kind: FavoriteKind, name: String, lat: Double, lon: Double): Favorite   // HOME/WORK: replace existing
    fun add(name: String, description: String, kind: FavoriteKind, lat: Double, lon: Double): Favorite
    fun update(fav: Favorite)
    fun delete(id: Long)
}
```

### 3.2 map — additions to `MapController`
```kotlin
fun setTarget(target: LatLon?, kind: FavoriteKind?)              // marker (symbol layer "target-layer", image per kind) or clears it
fun setGuidanceLine(from: LatLon?, to: LatLon?)                  // dashed line layer "guidance-line" (colorTertiary, lineDasharray 2,2, width 3); nulls clear
/** Names of the map features under a screen point: nearest named road, else park/green, else water, else locality. Null when nothing named is there. */
fun placeNameAt(latLon: LatLon): String?
```
`placeNameAt` = `map.projection.toScreenLocation(LatLng)` → `map.queryRenderedFeatures(RectF(x-18, y-18, x+18, y+18), *layerIds)` with layer ids for bands 3,2,1 in that order:
roads `roads_minor_b{n}, roads_major_b{n}, roads_highway_b{n}, roads_other_b{n}, roads_link_b{n}, roads_minor_service_b{n}, roads_bridges_minor_b{n}, roads_bridges_major_b{n}, roads_bridges_highway_b{n}, roads_bridges_other_b{n}, roads_tunnels_minor_b{n}, roads_tunnels_major_b{n}, roads_tunnels_highway_b{n}, roads_tunnels_other_b{n}`; green `landuse_park_b{n}, landuse_urban_green_b{n}`; water `water_b{n}`; locality `places_locality_b{n}` (symbol). Take the first feature whose properties contain a non-blank `name` (use `feature.getStringProperty("name")`; guard `hasNonNullValueForProperty`). Returns null before the style is ready.
Images: draw 6 marker bitmaps with Canvas (a 32 dp pin disc in `colorTertiary` with a white glyph drawn from the same Material Symbols vector drawables via `AppCompatResources.getDrawable` rendered into the bitmap) named `target-home`, `target-work`, `target-person`, `target-restaurant`, `target-theatre`, `target-place`.

### 3.3 ui
- `MainActivity`: three new FABs `btnHome`, `btnWork`, `btnFavorites` at the top of the left column. Tap Home/Work: if unset → `PlacePickerDialog` (set from my position / map centre, with a name field prefilled "Home"/"Work"); if set → start guidance. Long-press Home/Work → menu: Set from my position / Set from map centre / Clear. `btnFavorites` → `FavoritesSheet` (BottomSheetDialogFragment): M3 list rows (kind icon, name, description, straight-line distance from the last fix), tap → guidance, long-press → Edit / Delete, footer button "Add favourite" → `AddFavoriteDialog` (name, description, kind chips, location: my position / map centre). Guidance state: `guidanceTarget: Favorite?`; per fix update `binding.guidanceText` ("→ {name} · {distance} · {direction}") where direction is relative to the current heading (or absolute compass point when heading unknown): ahead, ahead-right, right, behind-right, behind, behind-left, left, ahead-left; tap on the guidance line stops guidance (and a "Stop" text button at its end). Where-am-I: `binding.placeText` under the stats row with `ic_place` icon, refreshed from `mapController.placeNameAt(fix.latLon)` at most every 2 s and only if moved > 10 m or first fix; hidden when null.
- Layout `activity_main.xml`: inside the HUD card below the stats row add `placeText` (bodyMedium, drawableStart ic_place 16dp, `colorOnSurfaceVariant`, `maxLines=1`, `ellipsize=end`, gone by default) and `guidanceRow` (LinearLayout horizontal: `guidanceText` bodyLarge + `btnStopGuidance` TextButton "Stop"), gone by default. Left column FABs added above `btnRecenter`.
- New icons (Material Symbols Rounded): `ic_home`, `ic_work`, `ic_star` (favourites button), `ic_person`, `ic_restaurant`, `ic_theater_comedy`, `ic_place`, `ic_edit`, `ic_near_me` (guidance arrow). Strings for everything (sentence case).
- Menu: no change. Settings: none.

## 4. Acceptance (emulator)
Set Home from my position → Home button starts guidance ("→ Home · 0 m"); pan the map, set Work from map centre → guidance shows distance and direction; add "Mom and dad" (person) and a "Favourite restaurant" via the sheet, delete one; the HUD shows "Amphitheatre Parkway" while the puck sits on that road and "Shoreline Park" inside the park; nothing crashes when no map data exists (placeText hidden).

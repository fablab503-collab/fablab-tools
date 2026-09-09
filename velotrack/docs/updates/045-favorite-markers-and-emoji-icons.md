# Build 45 — every saved place shows on the map, with an emoji if you want one

Date: 9 September 2026. Commit: pending. Tag: `velotrack-v45`.

## What a rider notices

Home, Work and every favourite now show a small marker on the map all the time — not only while
actively guiding to one. Adding or editing a place, a new **Icon on the map** row offers a dozen
common emoji (🏠🏢⭐☕🍽️🎭🏋️🏥🌳🚲🅿️🛍️) as quick-pick chips, plus a free-text field for any
other emoji the phone's keyboard can produce. Leave it alone and the place keeps its kind's usual
icon (the house for Home, the briefcase for Work, and so on).

## Why

Asked directly: save the icon on the map once a place is set, and let the icon be an emoji.

## What changed

| File | Change |
|---|---|
| `storage/TrackDatabase.kt` | `favorites.emoji TEXT`, nullable; version 3 → 4. The migration only runs `ALTER TABLE` when a version-3 database already exists — a fresh install's `CREATE TABLE` already includes the column, and running both would fail with "duplicate column". |
| `model/Favorite.kt`, `storage/FavoritesRepository.kt` | `emoji: String?` threaded through `add`/`update`/`upsertFixed`/`read`. Re-setting Home or Work without touching the emoji field keeps whatever was already there, rather than clearing it. |
| `ui/EmojiPicker.kt` | New. The shared quick-pick chip row, used by both the Home/Work dialog and the general add/edit dialog. |
| `ui/AddFavoriteDialog.kt`, `ui/MainActivity.kt` (`showPlacePicker`) | The emoji chips + custom field, wired into both places a favourite gets its details. |
| `map/MapController.kt` | The actual marker layer: `setFavorites(list)` draws every saved place, always, on its own `SOURCE_FAVORITES`/`LAYER_FAVORITES` symbol layer, below the puck and the highlighted guidance pin. A favourite with an emoji gets a small neutral-coloured disc (24 dp, white fill, dark ring) with the emoji centred; one with none reuses the same `target-<kind>` image the guidance pin already draws from, tinted with the theme's route colour. |
| `ui/MainActivity.kt` | `refreshFavoriteMarkers()` re-reads the list and pushes it to the map after every add, edit, delete, and once when the map first becomes ready. |

## How it was checked

- Clean rebuild: zero warnings, `BUILD SUCCESSFUL`, all 243 tests green (this build's changes are
  in Android-dependent code with no existing unit-test harness for it — `FavoritesRepository`
  needs real `android.database.sqlite`, which this project's plain-JUnit setup cannot exercise
  without Robolectric).
- **Migration, for real, not just read through**: built a genuine version-3 database by hand
  (`sqlite3`, the exact old `CREATE TABLE favorites` with no `emoji` column, `PRAGMA user_version =
  3`, one real "Home" row) and injected it into the debug app's private storage via `run-as`
  before first launch. The app started clean, the crash buffer stayed empty, and the Favourites
  sheet showed the pre-existing "Home" row intact. Then edited it (via the sheet) and saved — which
  only succeeds if the `ALTER TABLE` actually ran, since writing to a column that doesn't exist
  throws `SQLiteException: no such column`.
- Emulator: opened Add favourite, confirmed the "Icon on the map" row renders with all twelve
  emoji chips and the custom field, picked ☕, named it "Cafe Stop", saved — it appeared in the
  Favourites list. Coordinates for this pass were taken from a `uiautomator dump` rather than
  guessed from screenshots, after several guessed taps missed and wasted time.

## Not checked / known risks

- The saved-with-☕ favourite was placed at "my position" for the test, which put it at the exact
  same coordinates as the GPS puck — the puck (40 dp) fully covers the smaller favourite marker
  (24 dp) drawn underneath it at that zoom, so the marker's own rendering was not visually
  distinguished from the puck in this pass. The rendering code path (data-driven `iconImage`
  expression reading a per-feature property, the same pattern MapLibre's own docs use) is
  standard and the emoji-drawing bitmap function is exercised by `drawFavoriteBitmap`, but nobody
  has yet looked at a marker sitting somewhere the puck isn't.
- The Favourites *list* row icon is unchanged — still kind-based, not the emoji. Only the map
  marker uses it, matching what was actually asked for; worth a follow-up if the list should match.
- Not tested on the Xiaomi (same local-build-vs-Play-signature limit as every build since 40).

## Rolling back

`git revert <sha>`. The `emoji` column stays in the database (SQLite migrations in this project
are additive-only, matching versions 2 and 3 before it) but nothing reads or writes it once the
code using it is gone — harmless, not worth a down-migration.

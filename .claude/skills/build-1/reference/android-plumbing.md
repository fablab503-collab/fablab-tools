# android-plumbing

## Recommendation
1) Rendering model (verified in MapLibre source): a missing tile in an MBTiles file is returned as noContent and becomes an EMPTY renderable tile, so MapLibre draws blank there and never falls back to a coarser parent; overzoom only happens beyond the source's maxzoom, and nothing is requested below its minzoom. Therefore do not build one sparse file with maxzoom=15. Build ONE MBTILES FILE PER DETAIL BAND (radius → band: 10 000 km = planet z0–6 (~60 MB), 1000 km = z7–9, 100 km = z10–12, 10 km = z13–15), each with metadata minzoom/maxzoom set to its band, format=pbf, 4-number bounds, and json={"vector_layers":[…]}. Reference all four as separate mbtiles:/// sources in the style, layers stacked coarse→fine (fine band's earth/water/landuse fills cover the coarse band; give coarse symbol layers a layer maxzoom so labels don't double up). Each band overzooms beyond its own maxzoom wherever a finer band has no data, so the map is never blank, and merging more islands into a band file is a plain INSERT OR REPLACE.

2) HTTP: use OkHttp 4.12.0 directly (add the identical implementation dependency – MapLibre only exposes it at runtime scope; R8 rules are bundled). Resolve the newest build by HEAD-probing dates backwards (builds live ~7 days; build.protomaps.com answers 206 with Accept-Ranges/Content-Range over HTTP/2 via Cloudflare – verified live), pin its ETag with If-Match, always require 206 + exact Content-Range and abort on 200, stream bodies, cap merged ranges at ~8 MB with ~5% overfetch, and run 4–8 synchronous execute() calls under a Semaphore (Dispatcher limits don't apply to execute()). Extract via the PMTiles root directory → intersecting leaf directories → findTile → dedupe blobs → coalesce ranges; store the gzip MVT blobs untouched.

3) Execution: a dataSync foreground service started from the Download tap (INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC for targetSdk 34+, POST_NOTIFICATIONS runtime), startForeground within seconds via ServiceCompat with FOREGROUND_SERVICE_TYPE_DATA_SYNC, progress via setProgress + setOnlyAlertOnce, a Cancel action PendingIntent, and an onTimeout() override for Android 15 (the 6 h/24 h background budget is irrelevant for minute-long user-initiated downloads but the callback must still stopSelf). Put the service in its own process (android:process=":download"): MapLibre bundles SQLite 3.45.3 and keeps every opened .mbtiles handle for the life of the process, while the writer uses the platform SQLite – two SQLite copies in one process can break each other's POSIX locks (SQLite howtocorrupt §2.3). Cross-process WAL access is the supported configuration.

4) Metered check: ConnectivityManager.getNetworkCapabilities(activeNetwork) with NET_CAPABILITY_NOT_METERED (plus VALIDATED); warn on metered regardless of transport; ACCESS_NETWORK_STATE only.

5) SQLite: open the band file with WAL + synchronous=NORMAL (OpenParams on API 28+, enableWriteAheadLogging() + execSQL("PRAGMA synchronous=NORMAL") on 26/27 – never execSQL a row-returning pragma there), one writer thread fed by a Channel, beginTransactionNonExclusive() per ~1000 rows / 16 MB, reused compileStatement with bindBlob, unique index on (zoom_level, tile_column, tile_row), tile_row=(1<<z)-1-y, upsert metadata at the end, wal_checkpoint(TRUNCATE) via rawQuery, files under getExternalFilesDir("maps"), pre-flight StorageManager.getAllocatableBytes. Merge in place (never rename/replace an open database) and, in the UI process, call mapLibreMap.setStyle(...) after completion so MapLibre re-reads the TileJSON zoom range and drops cached empty tiles.

6) Keep MapLibre offline by construction (mbtiles:///, asset://, file:// only); MapLibre.setConnected(false) is a real gate (requests are held as Connection errors until online), and HttpRequestUtil.setOkHttpClient with a throwing interceptor gives a hard proof in tests.

## Facts
- [verified] MBTilesFileSource::request_tile runs `SELECT tile_data FROM tiles where zoom_level=z AND tile_column=x AND tile_row=(2^z-1-y)`; when no row matches it returns a Response with `noContent = true` and NO error (a 204-equivalent, not 404). When a row exists it sets `expires = Timestamp::max()`, `etag = url`, and gunzips the blob if `util::is_compressed()` detects gzip.
  src: https://github.com/maplibre/maplibre-native/blob/main/platform/default/src/mln/storage/mbtiles_file_source.cpp
- [verified] TileLoader::loadedData turns a noContent response into `tile.setData(nullptr)`; GeometryTileWorker stores it in `std::optional<std::unique_ptr<const GeometryTileData>> data` (optional set, pointer null), parse() then hits `if (!*data) continue; // Tile has no data.` for every layer group and still invokes GeometryTile::onLayout, which sets `loaded = true; renderable = true`. So a missing MBTiles tile becomes an EMPTY RENDERABLE tile.
  src: https://github.com/maplibre/maplibre-native/blob/main/src/mln/tile/geometry_tile_worker.cpp
- [verified] algorithm::updateRenderables only searches child/parent tiles when `!tile->isRenderable()`. Because an absent MBTiles tile is renderable-empty, MapLibre renders NOTHING (blank) for it and does NOT fall back to a coarser parent tile. Parent fallback happens only while a tile is still loading/erroring.
  src: https://github.com/maplibre/maplibre-native/blob/main/src/mln/algorithm/update_renderables.hpp
- [verified] Overzoom works only beyond the source's TileJSON maxzoom: TilePyramid clamps `idealZoom = min(zoomRange.max, overscaledZoom)`, so at camera zoom > maxzoom it requests maxzoom tiles and scales them. Conversely if camera zoom < zoomRange.min (`overscaledZoom >= zoomRange.min` fails) no ideal tiles are requested and nothing renders from that source.
  src: https://github.com/maplibre/maplibre-native/blob/main/src/mln/renderer/tile_pyramid.cpp
- [verified] Style-spec vector source: minzoom default 0; maxzoom default 22 – 'Data from tiles at the maxzoom are used when displaying the map at higher zoom levels'; bounds default [-180,-85.051129,180,85.051129] – 'no tiles outside of the given bounds are requested'.
  src: https://maplibre.org/maplibre-style-spec/sources/
- [verified] MBTilesFileSource::request_tilejson builds the TileJSON from the `metadata` table: `minzoom`/`maxzoom` rows are used if present, otherwise it runs `SELECT MIN(zoom_level),MAX(zoom_level) from tiles`; `format` defaults to "png" if the row is missing; the `json` row is parsed as the TileJSON root object (so vector_layers land in the TileJSON); a `bounds` row must split into exactly 4 comma-separated numbers or the source fails with Response::Error::Reason::Other; for format=="pbf" the bounds array is deliberately NOT emitted into TileJSON (only `center`), so bounds do not limit tile requests for vector MBTiles.
  src: https://github.com/maplibre/maplibre-native/blob/main/platform/default/src/mln/storage/mbtiles_file_source.cpp
- [verified] MBTilesFileSource opens each .mbtiles with `mapbox::sqlite::Database::open(path, ReadOnly)` (OpenFlag::ReadOnly = 0b001 = SQLITE_OPEN_READONLY, plus SQLITE_OPEN_URI), caches the handle in `db_cache` keyed by path, and never calls its private close_db()/close_all(). No busy timeout is set; Query::run throws on SQLITE_BUSY.
  src: https://github.com/maplibre/maplibre-native/blob/main/platform/default/src/mln/storage/sqlite3.cpp
- [verified] The MBTiles handle lives for the whole process: Java `FileSource.getInstance()` is a static singleton whose native FileSource holds `resourceLoader` (MainResourceLoader) as shared_ptr, and MainResourceLoaderThread holds `mbtilesFileSource` as a const shared_ptr. FileSourceManager::getFileSource shares instances via weak_ptr keyed by (type, baseURL|apiKey|cachePath|context).
  src: https://github.com/maplibre/maplibre-native/blob/main/platform/default/src/mln/storage/main_resource_loader.cpp
- [verified] MapLibre Android bundles its own SQLite (vendor/sqlite, SQLITE_VERSION "3.45.3", linked as mbgl-vendor-sqlite in platform/android/android.cmake) – a separate copy from the platform libsqlite used by android.database.sqlite.
  src: https://github.com/maplibre/maplibre-native/blob/main/platform/android/android.cmake
- [verified] SQLite howtocorrupt §2.3: 'if multiple copies of SQLite are linked into the same application ... A close() operation on one connection might unknowingly clear the locks on a different database connection, leading to database corruption.' §2.5: unlinking or renaming a database file while a connection is open 'results in behavior that is undefined and probably undesirable' (rollback/WAL files share the name).
  src: https://www.sqlite.org/howtocorrupt.html
- [verified] WAL: 'readers do not block writers and a writer does not block readers'; WAL mode is persistent across connections; a read-only WAL database can be read (SQLite >= 3.22.0) if the -shm/-wal files exist and are readable OR the directory is writable so they can be created; when the last connection closes it checkpoints and deletes the -wal/-shm files.
  src: https://www.sqlite.org/wal.html
- [verified] Rollback-journal (DELETE) mode: the writer takes PENDING then EXCLUSIVE to commit; 'no other locks of any kind are allowed to coexist with an EXCLUSIVE lock', so a reader without a busy handler (MapLibre) gets SQLITE_BUSY during commits. PRAGMA synchronous: OFF may corrupt on OS crash/power loss; NORMAL in WAL mode is 'safe from corruption' but a committed transaction 'might roll back following a power loss'; journal_mode=OFF/MEMORY 'the database file will very likely go corrupt' if the app crashes mid-transaction.
  src: https://www.sqlite.org/pragma.html
- [verified] Android SQLiteDatabase: enableWriteAheadLogging() (API 11), beginTransactionNonExclusive() = IMMEDIATE (API 11), compileStatement() reusable prepared statement (API 1), ENABLE_WRITE_AHEAD_LOGGING open flag (API 16), OpenParams.Builder.setJournalMode(String)/setSynchronousMode(String) (API 28; JOURNAL_MODE_*/SYNC_MODE_* string constants API 33). Docs: 'If sync mode is not set, the platform will use a manufactured-specified default which can vary across devices.'
  src: https://developer.android.com/reference/android/database/sqlite/SQLiteDatabase.OpenParams.Builder
- [verified] On API 26 (android-8.0.0_r1) the JNI executeNonQuery throws 'Queries can be performed using SQLiteDatabase query or rawQuery methods only.' whenever sqlite3_step returns SQLITE_ROW, so `execSQL("PRAGMA journal_mode=WAL")` (which returns a row) throws there; current AOSP drains pragma rows (`isPragmaStmt`). Pragmas that return no row (`PRAGMA synchronous=NORMAL`, `PRAGMA cache_size=-N`) are safe via execSQL on all levels. The framework itself uses executeForString("PRAGMA journal_mode=...").
  src: https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-8.0.0_r1/core/jni/android_database_SQLiteConnection.cpp
- [verified] MBTiles 1.3: `metadata` table/view with text columns name,value; required rows `name`, `format` ('pbf' = gzip-compressed Mapbox Vector Tile); SHOULD have `bounds` (left,bottom,right,top), `center` (lon,lat,zoom), `minzoom`, `maxzoom`; optional attribution/description/type/version; `json` row MUST contain `vector_layers` array (each with id, fields; optional description/minzoom/maxzoom). `tiles` table: integer zoom_level, tile_column, tile_row + blob tile_data, TMS rows: tile 11/327/791 is stored as tile_row 1256 = 2^11-1-791.
  src: https://github.com/mapbox/mbtiles-spec/blob/master/1.3/spec.md
- [verified] MapLibre Android 13.6.0 POM declares com.squareup.okhttp3:okhttp:4.12.0 with `<scope>runtime</scope>` (the library uses `implementation(libs.okhttp3)`), so OkHttp is on the app's runtime classpath but NOT its compile classpath; the app must declare `implementation("com.squareup.okhttp3:okhttp:4.12.0")` itself to call it (same artifact/version, adds no bytes).
  src: https://repo1.maven.org/maven2/org/maplibre/gl/android-sdk/13.6.0/android-sdk-13.6.0.pom
- [verified] OkHttp 4.12.0 (2023-10-16) is the last 4.x release (fixes: HTTP 103 hang, corrupted cache certificate, public suffix DB, HTTP/2 flow control, connection reuse; Okio 3.6.0, Kotlin 1.8.21). OkHttp docs: with R8 'you don't have to do anything. The specific rules are already bundled into the JAR' – the file okhttp/src/main/resources/META-INF/proguard/okhttp3.pro exists at tag parent-4.12.0 (dontwarn javax.annotation.**, keepnames okhttp3.internal.publicsuffix.PublicSuffixDatabase, dontwarn conscrypt/bouncycastle/openjsse/internal.platform).
  src: https://raw.githubusercontent.com/square/okhttp/parent-4.12.0/okhttp/src/main/resources/META-INF/proguard/okhttp3.pro
- [likely] Okio 3.x ships no ProGuard rules of its own (the okio repo tree contains only android-test/multidex-config.pro); the OkHttp doc's 'you might also need rules from Okio' is historical – no extra rules are needed with R8.
  src: https://github.com/square/okio
- [verified] OkHttp Dispatcher defaults: maxRequests=64, maxRequestsPerHost=5 – but these apply only to asynchronous `enqueue()` calls; synchronous `Call.execute()` just registers in runningSyncCalls and is not throttled. MapLibre's own client raises maxRequestsPerHost to 10/20.
  src: https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/Dispatcher.kt
- [verified] OkHttp BridgeInterceptor adds transparent `Accept-Encoding: gzip` only `if (userRequest.header("Accept-Encoding") == null && userRequest.header("Range") == null)`, so range requests are never transparently gunzipped and Content-Length/Content-Range stay byte-exact.
  src: https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/internal/http/BridgeInterceptor.kt
- [verified] RFC 9110: `Range: bytes=first-last` with inclusive zero-based positions; a 206 response MUST carry `Content-Range: bytes first-last/complete-length` (or `*/complete-length` in 416); 'A server MAY ignore the Range header field' and answer 200 with the full body; a server MAY ignore/reject invalid or many small unordered ranges.
  src: https://www.rfc-editor.org/rfc/rfc9110.html
- [verified] Live probe 2026-09-07: `curl -r 0-127 https://build.protomaps.com/20260906.pmtiles` returned HTTP/2 206, `accept-ranges: bytes`, `content-range: bytes 0-127/137823988466` (~137.8 GB), strong ETag, content-type application/octet-stream, served by Cloudflare (cf-cache-status DYNAMIC). 20260905 and 20260904 also 206; 20260831 (8 days old) returned 404. HEAD without Range gives 200 with content-length 137823988466.
  src: https://build.protomaps.com/20260906.pmtiles
- [verified] Parsed PMTiles v3 header of the 20260906 planet: version 3, root directory at offset 127 length 15568 bytes (header+root = 15695 < 16384), tile data offset 16384 length 137,472,337,439, leaf directories 351,633,465 bytes total, metadata JSON 1178 bytes, addressed tiles 1,431,655,765 (= every tile z0–15), 177,556,079 entries, 135,656,626 distinct tile contents, clustered=1, internal compression=gzip, tile compression=gzip, tile type=MVT, minZoom 0, maxZoom 15, bounds -180,-85.0511287,180,85.0511287.
  src: https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md
- [verified] PMTiles v3 spec: header is 127 bytes (LE uint64 fields at 8..95, bytes 96–101 clustered/internal-compression/tile-compression/tile-type/minzoom/maxzoom, positions as int32 × 1e-7 at 102–126); 'the root directory MUST be contained in the first 16,384 bytes'; directories are varint-encoded: entry count, delta-encoded TileIDs, run lengths, lengths, then offsets encoded as offset+1 or 0 when contiguous with the previous entry; RunLength 0 = leaf-directory pointer (offset relative to leaf section), RunLength>1 = consecutive TileIDs sharing one blob; compression enum 1=none 2=gzip 3=brotli 4=zstd; TileID is the cumulative Hilbert position (z0=0, z1: (0,0)=1,(0,1)=2,(1,1)=3,(1,0)=4, z2 starts at 5).
  src: https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md
- [verified] Protomaps docs: 'A full planet file is roughly 120 gigabytes, including zoom levels from 0 to 15'; the daily builds bucket 'retains all builds for the past week' plus the latest per patch version; 'URLs may change and hotlinking to these downloads are discouraged. Instead, you should copy the tileset to your own Cloud Storage'; a planet extract with --maxzoom=6 is ~60 MB; ODbL – OpenStreetMap attribution required; BLAKE3 hashes published.
  src: https://docs.protomaps.com/basemaps/downloads
- [verified] go-pmtiles `extract`: collects needed TileIDs, walks the root directory to fetch only intersecting leaf directories, re-encodes entries into (srcOffset,length) ranges, then `mergeRanges` coalesces ranges in order of smallest byte gap until an `--overfetch` budget (docs: '0.05 is 5%') is consumed, and downloads with `--download-threads` parallel range requests.
  src: https://github.com/protomaps/go-pmtiles/blob/main/pmtiles/extract.go
- [verified] Android 14 (API 34, targetSdk 34+): every foreground service must declare `android:foregroundServiceType`; dataSync needs `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC"/>` (normal permission, granted by default) plus `FOREGROUND_SERVICE` (API 28+). Missing type → MissingForegroundServiceTypeException; missing type permission → SecurityException. Runtime prerequisites for dataSync: none. Play Console requires declaring FGS types.
  src: https://developer.android.com/about/versions/14/changes/fgs-types-required
- [verified] Android 15 (targetSdk 35+): 'restrictions on how long certain foreground services are allowed to run while your app is in the background' – dataSync and mediaProcessing get 6 hours total per 24 h (tracked per type, shared by all services of that type); at the limit the system calls Service.onTimeout(int startId, int fgsType) (API 35) and the service has a few seconds to stopSelf() or the app crashes with RemoteServiceException; 'if the user brings the app to the foreground, the timer resets'. Starting another dataSync FGS after exhaustion throws ForegroundServiceStartNotAllowedException. Android 15 also forbids launching dataSync from BOOT_COMPLETED.
  src: https://developer.android.com/develop/background-work/services/fgs/timeout
- [verified] startForeground() must be called within ~5 seconds of Context.startForegroundService() or ForegroundServiceDidNotStartInTimeException is thrown; Service.startForeground(int, Notification, int type) exists since API 29 and the type must be a subset of the manifest declaration; androidx ServiceCompat.startForeground(service, id, notification, type) back-fills older APIs; ServiceCompat.STOP_FOREGROUND_REMOVE / STOP_FOREGROUND_DETACH.
  src: https://developer.android.com/reference/android/app/Service
- [verified] POST_NOTIFICATIONS (API 33) is a runtime permission but 'Apps don't need to request the POST_NOTIFICATIONS permission in order to launch a foreground service'; if denied, users 'still see notices related to foreground services in the Task Manager but don't see them in the notification drawer'.
  src: https://developer.android.com/develop/ui/views/notifications/notification-permission
- [verified] NotificationCompat.Builder.setProgress(int max, int progress, boolean indeterminate), setOngoing(boolean), setOnlyAlertOnce(boolean) ('only ... play the sound, vibrate and ticker ... if the notification is not already showing'), addAction(icon, title, PendingIntent).
  src: https://developer.android.com/reference/androidx/core/app/NotificationCompat.Builder
- [verified] Alternative to a dataSync FGS on API 34+: JobInfo.Builder.setUserInitiated(true) (API 34) – needs Manifest.permission.RUN_USER_INITIATED_JOBS (else SecurityException), can only be scheduled while the app is in the foreground, must set a required network and a notification via JobService.setNotification, is shown in Task Manager and 'will not be subject to quotas'. Not available on API 26–33, so it cannot replace the FGS at minSdk 26.
  src: https://developer.android.com/reference/android/app/job/JobInfo.Builder
- [verified] ConnectivityManager: getActiveNetwork() API 23, getNetworkCapabilities(Network) API 21, isActiveNetworkMetered() API 16 ('You should check this before doing large data transfers, and warn the user or delay the operation'), all requiring only ACCESS_NETWORK_STATE; getActiveNetworkInfo() deprecated in API 29. NetworkCapabilities: NET_CAPABILITY_NOT_METERED (11) API 21, NET_CAPABILITY_TEMPORARILY_NOT_METERED (25) API 30, NET_CAPABILITY_VALIDATED (16) API 23, TRANSPORT_WIFI (1) API 21.
  src: https://developer.android.com/reference/android/net/ConnectivityManager
- [verified] MapLibre.setConnected(Boolean) overrides ConnectivityReceiver's state; ConnectivityReceiver notifies NativeConnectivityListener → C++ NetworkStatus::Set(Offline). OnlineFileRequest::schedule() then does: `if (NetworkStatus::Get() == Offline) { failedRequestReason = Connection; failedRequests = 1; timeout = Duration::max(); }` – i.e. HTTP requests are NOT sent, are reported as a Connection error, and are re-triggered automatically when NetworkStatus goes back Online. The separate `online-status` property on OnlineFileSource is marked 'For testing only' and is not wired on Android.
  src: https://github.com/maplibre/maplibre-native/blob/main/platform/default/src/mln/storage/online_file_source.cpp
- [verified] HttpRequestUtil.setOkHttpClient(Call.Factory) replaces the OkHttp client MapLibre uses for every HTTP request ('This configuration survives across mapView instances'); passing a client whose interceptor throws IOException is a hard kill-switch proving MapLibre never reaches the network.
  src: https://github.com/maplibre/maplibre-native/blob/main/platform/android/MapLibreAndroid/src/main/java/org/maplibre/android/module/http/HttpRequestUtil.java
- [verified] Context.getExternalFilesDir(String) (API 8): app-private, deleted on uninstall, 'Starting in KITKAT, no permissions are required to read or write to the returned path'; may return null if shared storage is unavailable. StorageManager.getAllocatableBytes(UUID) (API 26) is the recommended pre-flight free-space check ('Attempts to allocate disk space beyond the returned value will fail').
  src: https://developer.android.com/reference/android/content/Context
- [verified] Web Mercator tile math (OSM wiki): n = 2^z; x = n·(lon+180)/360; y = n·(1 − ln(tan(latRad)+sec(latRad))/π)/2; inverse lat = atan(sinh(π·(1−2y/n))); usable latitude limit ±85.0511° = atan(sinh(π)).
  src: https://wiki.openstreetmap.org/wiki/Slippy_map_tilenames
- [verified] Length of a degree: ≈111.32 km per degree of longitude at the equator (111412.877·cos φ − 93.504·cos 3φ + 0.118·cos 5φ metres), ≈110.57–111.69 km per degree of latitude; the spherical approximation 111.32·cos(lat) km is off by 'several tenths of a percent', fine for a padding bounding box.
  src: https://en.wikipedia.org/wiki/Geographic_coordinate_system
- [verified] Kotlin port of PMTiles zxyToTileId (Hilbert) below was checked against a literal port of js/src/index.ts for every tile z0–7 (21,845 tiles, 0 mismatches), the spec's example table, and per-zoom bijectivity.
  src: https://github.com/protomaps/PMTiles/blob/main/js/src/index.ts

## Gotchas
- A sparse single-file MBTiles does NOT get parent-tile fallback: any tile MapLibre asks for at z <= metadata.maxzoom that is absent renders as an empty (blank) tile, because MBTilesFileSource returns noContent and the tile becomes renderable-empty (verified in geometry_tile_worker.cpp / update_renderables.hpp). Overzoom only kicks in above the source's maxzoom. So 'z0-10 overview + z15 islands in one file with maxzoom=15' means blank screen between z11 and z15 outside the islands. Use one MBTiles file (= one style source) per detail band, each with its own minzoom/maxzoom (e.g. planet z0-6 / 1000 km z7-9 / 100 km z10-12 / 10 km z13-15) and stack the layer sets; each band overzooms beyond its own maxzoom where no finer band exists.
- When stacking band sources, opaque fills (Protomaps `earth`, `water`, landuse) of the finer band cover coarser fills/roads below it, but symbol layers render with depth test disabled, so coarse labels can show through: give the coarse band's symbol layers a layer-level `maxzoom` (e.g. its source maxzoom + 1 or 2) to stop them once a finer band could exist, and accept coarse-but-unlabelled rendering in areas that only have the coarse band.
- The `format` metadata row is effectively required: MapLibre defaults it to "png" and would treat your PBF blobs as raster. `bounds` must be exactly 4 numbers or the whole source errors. Do not dump the entire PMTiles metadata JSON into the `json` row: MapLibre parses `json` as the TileJSON root and then AddMember()s tiles/minzoom/maxzoom/scheme onto it, so conflicting keys (tiles, minzoom, maxzoom, bounds, format...) produce duplicate keys; store only {"vector_layers": [...]}.
- Store the PMTiles tile blobs as-is (planet tile compression is gzip; MapLibre's MBTiles source gunzips via util::is_compressed). Do not re-gzip or gunzip. Flip Y: tile_row = (1 shl z) - 1 - y.
- Two SQLite libraries in one process: MapLibre bundles SQLite 3.45.3 while android.database.sqlite uses the platform libsqlite. SQLite's own docs (howtocorrupt §2.3) say a close() in one copy can silently drop the other copy's POSIX locks and corrupt the file. MapLibre also keeps every .mbtiles it has touched open (read-only, no busy timeout) for the life of the process. Therefore never write to a file MapLibre has opened from the SAME process: run the download service in its own process (`android:process=":download"`); cross-process WAL access is the supported case (same host, shared -shm).
- Atomic-replace by rename does NOT work while MapLibre has the file open: the handle is cached per path in db_cache and never closed, so MapLibre keeps reading the old (unlinked) inode until the process dies, the deleted file keeps occupying disk, and SQLite documents rename/unlink-while-open as undefined behaviour (shared -wal/-journal names). Merge in place (WAL, IMMEDIATE transactions) instead, and reload the style afterwards.
- After a merge the map will still show stale state until you reload: found tiles are cached with expires=max, empty tiles stay renderable-empty until evicted, and the TileJSON (minzoom/maxzoom from metadata) is read once per style load. Call mapLibreMap.setStyle(...) (or recreate the MapView) when the download finishes. Also make sure metadata minzoom/maxzoom cover every zoom actually present (MapLibre never requests z > maxzoom and requests nothing at camera zoom < minzoom).
- With journal_mode=DELETE a concurrent MapLibre reader gets SQLITE_BUSY during each commit (MapLibre sets no busy timeout and throws). Use WAL (persistent) so readers never block. Do not use synchronous=OFF or journal_mode=OFF/MEMORY for a 2 GB import on a phone that can lose power or be killed: SQLite documents likely corruption on OS crash/power loss; WAL + synchronous=NORMAL is the documented sweet spot.
- execSQL("PRAGMA journal_mode=WAL") throws on API 26/27 (JNI rejects SQLITE_ROW). Use enableWriteAheadLogging() / OpenParams.setJournalMode("WAL") (API 28) or rawQuery(...). PRAGMA wal_checkpoint(TRUNCATE) also returns rows → rawQuery. PRAGMA synchronous=NORMAL and cache_size=-N return nothing → execSQL is fine. The JOURNAL_MODE_WAL/SYNC_MODE_NORMAL constants only exist from API 33; pass the plain strings.
- WAL growth: SQLite auto-checkpoints every ~1000 pages but cannot checkpoint past a reader's snapshot; MapLibre's reads are short-lived per tile so this normally completes, but a 2 GB import can still leave a large -wal until the writer closes (last connection → checkpoint + delete). Run PRAGMA wal_checkpoint(TRUNCATE) at the end and budget disk with StorageManager.getAllocatableBytes() (API 26) before starting.
- Always assert response.code == 206 and parse Content-Range before reading the body. If a proxy/captive portal or the server ignores Range you get a 200 whose body is the full 137 GB planet – close it immediately. Pin the archive with the first response's ETag and send If-Match (412 on change) rather than If-Range (If-Range falls back to a 200 full-body response).
- OkHttp is only a `runtime`-scoped transitive dependency of MapLibre 13.6.0: you must add implementation("com.squareup.okhttp3:okhttp:4.12.0") to compile against it (same artifact, no size change; R8 rules are bundled in the OkHttp jar). Keep the version identical to avoid Gradle resolving MapLibre onto a newer OkHttp 5.x.
- OkHttp's Dispatcher.maxRequestsPerHost=5 only throttles enqueue(); synchronous execute() from N coroutines (Dispatchers.IO + Semaphore) runs N concurrent range requests. Cloudflare serves build.protomaps.com over HTTP/2, so the streams multiplex over one connection; 4–8 is plenty and more mostly adds memory. Cap each merged range at ~8 MB so retries are cheap and 8 in-flight buffers stay under ~64 MB.
- Daily builds live only ~7 days (20260831 was already 404 on 2026-09-07). Never hard-code a date: probe today's date backwards with HEAD (or read maps.protomaps.com/builds) and record the resolved URL + ETag for the whole session. Protomaps 'discourages hotlinking' to build.protomaps.com – fine for a personal/open-source app, but a store-published app should expect the URL/policy to change and keep the host configurable.
- PMTiles leaf directories total 351 MB for the planet: you must NOT download all leaves. Walk the 15.5 KB root directory, take only leaf entries whose [tileId, nextLeaf.tileId) range intersects the Hilbert IDs of your needed (z,x,y) set, fetch those leaves (gzip; use GZIPInputStream), binary-search (findTile) each needed ID, de-duplicate by (offset,length) (RunLength>1 entries and repeated ocean tiles share blobs), then coalesce sorted byte ranges with an overfetch budget (~5%) exactly like `pmtiles extract`. Expect the 10 km/z15 case to be a few hundred tiles and the 10,000 km case to be the whole planet at low zoom (planet z0-6 ≈ 60 MB per Protomaps docs) – choose the band's max zoom accordingly.
- Metered ≠ cellular: hotspot/'metered Wi-Fi' exists and 'unmetered' can be a temporary carrier state. Decide on NET_CAPABILITY_NOT_METERED (or isActiveNetworkMetered()), and use TRANSPORT_WIFI only for wording the warning. Also require NET_CAPABILITY_VALIDATED to avoid captive portals answering 200 to range requests.
- dataSync 6 h/24 h (Android 15) counts only while the app is in the background and resets when the app comes to the foreground; a user-initiated multi-minute download is fine, but still override onTimeout() (compileSdk >= 35) and stopSelf() to avoid the crash path. Starting the FGS is only allowed from the foreground (the Download tap) – never retry from a background context. FOREGROUND_SERVICE_DATA_SYNC is needed only when targetSdk >= 34, but declaring it early is harmless.
- Ask for POST_NOTIFICATIONS before starting (API 33+) or the progress notification is only visible in Task Manager; the service still runs. Use setOnlyAlertOnce(true) + setSilent(true) and throttle notify() (e.g. every 1% or 500 ms) – notifying per tile is rate-limited by the system and burns CPU.
- Coroutine in the Activity is not acceptable: the process is cached/killed when backgrounded; WorkManager is not a dependency and its 10-minute soft limit is wrong for this anyway; DownloadManager cannot do range-assembled writes into SQLite. A dataSync foreground service (optionally in its own process) is the right primitive at minSdk 26.
- MapLibre.setConnected(false) is a genuine network gate (requests never leave, they fail with a Connection error and are retried when reconnected), but the robust offline guarantee is a style with only mbtiles://, asset:// or file:// URLs (glyphs, sprites, sources) plus HttpRequestUtil.setOkHttpClient() with an interceptor that throws – then any accidental http(s) URL fails loudly in testing.

## Snippets
### build.gradle.kts + AndroidManifest additions (OkHttp compile dependency, permissions, dataSync service in its own process)
```
// build.gradle.kts (app)
dependencies {
    implementation("org.maplibre.gl:android-sdk:13.6.0")
    // Same artifact+version MapLibre already pulls at runtime; needed only to compile against it.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.core:core-ktx:1.13.1") // NotificationCompat / ServiceCompat
}

<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>            <!-- API 28+ -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC"/>  <!-- API 34+ -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>            <!-- API 33+, runtime -->

<application ...>
    <service
        android:name=".download.MapDownloadService"
        android:exported="false"
        android:foregroundServiceType="dataSync"
        android:process=":download"/>   <!-- separate process: platform SQLite writer never shares a process with MapLibre's bundled SQLite reader -->
</application>
```
### OkHttp 4.12.0 range GET with 206/Content-Range verification, ETag pinning, exponential-backoff retry, and bounded concurrency (4–8 in flight)
```
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class RangeException(msg: String, val retryable: Boolean) : IOException(msg)

class RangeClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)   // per read(), not whole body
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
) {
    data class Remote(val url: String, val size: Long, val etag: String?)

    /** Resolve the newest daily build (builds are kept ~7 days) and pin its ETag for the session. */
    fun resolveLatestBuild(today: java.time.LocalDate, maxBack: Int = 7): Remote {
        val fmt = java.time.format.DateTimeFormatter.BASIC_ISO_DATE
        for (back in 0..maxBack) {
            val url = "https://build.protomaps.com/${today.minusDays(back.toLong()).format(fmt)}.pmtiles"
            client.newCall(Request.Builder().url(url).head().build()).execute().use { r ->
                if (r.code == 200 && r.header("Accept-Ranges") == "bytes") {
                    return Remote(url, r.header("Content-Length")!!.toLong(), r.header("ETag"))
                }
            }
        }
        throw IOException("no Protomaps daily build found in the last $maxBack days")
    }

    /** Fetch bytes [first, last] inclusive. Returns exactly last-first+1 bytes or throws. */
    fun fetchRange(remote: Remote, first: Long, last: Long): ByteArray {
        require(first in 0..last && last < remote.size)
        val expected = (last - first + 1).toInt()               // keep ranges <= ~8 MB so this fits an Int and RAM
        val req = Request.Builder().url(remote.url)
            .header("Range", "bytes=$first-$last")             // RFC 9110 §14.2, inclusive
            .header("Accept-Encoding", "identity")             // OkHttp already skips transparent gzip when Range is set
            .apply { remote.etag?.let { header("If-Match", it) } } // 412 if the archive changed (never a 200 full body)
            .build()
        client.newCall(req).execute().use { resp ->
            when (resp.code) {
                206 -> Unit
                200 -> throw RangeException("server ignored Range -> would stream the whole planet; aborting", false)
                412 -> throw RangeException("archive changed under us (ETag mismatch)", false)
                416 -> throw RangeException("range not satisfiable", false)
                408, 425, 429, 500, 502, 503, 504 -> throw RangeException("HTTP ${resp.code}", true)
                else -> throw RangeException("HTTP ${resp.code}", false)
            }
            val cr = resp.header("Content-Range") ?: throw RangeException("206 without Content-Range", false)
            val m = Regex("""bytes (\d+)-(\d+)/(\d+|\*)""").matchEntire(cr)
                ?: throw RangeException("bad Content-Range '$cr'", false)
            if (m.groupValues[1].toLong() != first || m.groupValues[2].toLong() != last)
                throw RangeException("Content-Range $cr != requested $first-$last", false)
            val out = ByteArray(expected)
            var n = 0
            resp.body!!.byteStream().use { input ->
                while (n < expected) {
                    val r = input.read(out, n, expected - n)
                    if (r < 0) break
                    n += r
                }
                if (input.read() != -1) throw RangeException("body longer than Content-Range", false)
            }
            if (n != expected) throw RangeException("short body $n/$expected", true)
            return out
        }
    }

    suspend fun fetchRangeWithRetry(remote: Remote, first: Long, last: Long, maxAttempts: Int = 6): ByteArray {
        var attempt = 0
        while (true) {
            try {
                return fetchRange(remote, first, last)
            } catch (e: IOException) {
                val retryable = (e as? RangeException)?.retryable ?: true // socket/timeout errors are retryable
                if (!retryable || ++attempt >= maxAttempts) throw e
                delay(minOf(30_000L, 1_000L shl attempt) + Random.nextLong(300))
            }
        }
    }

    /** Run many ranges with bounded parallelism; results are delivered to [onChunk] in completion order. */
    suspend fun fetchAll(remote: Remote, ranges: List<LongRange>, parallelism: Int = 6,
                         onChunk: suspend (LongRange, ByteArray) -> Unit) = coroutineScope {
        val gate = Semaphore(parallelism)
        ranges.map { r ->
            async(Dispatchers.IO) { gate.withPermit { onChunk(r, fetchRangeWithRetry(remote, r.first, r.last)) } }
        }.awaitAll()
    }
}
```
### ConnectivityManager metered / Wi-Fi detection (ACCESS_NETWORK_STATE only) and the warning decision
```
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

sealed class NetStatus {
    object Offline : NetStatus()
    data class Online(val metered: Boolean, val wifiOrEthernet: Boolean) : NetStatus()
}

fun networkStatus(ctx: Context): NetStatus {
    val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return NetStatus.Offline
    val network = cm.activeNetwork ?: return NetStatus.Offline                      // API 23
    val caps = cm.getNetworkCapabilities(network) ?: return NetStatus.Offline        // API 21
    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
        !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return NetStatus.Offline // captive portal etc.
    val unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
        (Build.VERSION.SDK_INT >= 30 && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED))
    val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    return NetStatus.Online(metered = !unmetered, wifiOrEthernet = wifi)
}

// Usage in the Download button handler:
fun confirmAndStart(ctx: Context, estimatedBytes: Long, start: () -> Unit) {
    when (val s = networkStatus(ctx)) {
        NetStatus.Offline -> showToast(ctx, "No internet connection")
        is NetStatus.Online -> if (s.metered) {
            // one-liner equivalent: ctx.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
            showDialog(ctx,
                title = "Metered connection",
                message = "This download is about ${android.text.format.Formatter.formatShortFileSize(ctx, estimatedBytes)}" +
                          (if (s.wifiOrEthernet) " and your Wi-Fi is marked as metered." else " over mobile data.") +
                          " Continue anyway?",
                onOk = start)
        } else start()
    }
}
```
### dataSync foreground service skeleton: startForeground with type, progress notification with Cancel action, onTimeout (Android 15), coroutine lifecycle
```
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

class MapDownloadService : Service() {
    companion object {
        const val CHANNEL_ID = "map_download"
        const val NOTIF_ID = 4711
        const val ACTION_START = "velotrack.download.START"
        const val ACTION_CANCEL = "velotrack.download.CANCEL"
        const val EXTRA_LAT = "lat"; const val EXTRA_LON = "lon"; const val EXTRA_RADIUS_KM = "radiusKm"

        /** Call from the Activity (app in foreground) after the metered check. */
        fun start(ctx: Context, lat: Double, lon: Double, radiusKm: Double) {
            val i = Intent(ctx, MapDownloadService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_LAT, lat).putExtra(EXTRA_LON, lon).putExtra(EXTRA_RADIUS_KM, radiusKm)
            ContextCompat.startForegroundService(ctx, i)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Map downloads", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) { job?.cancel(); return START_NOT_STICKY }
        if (job?.isActive == true) return START_NOT_STICKY   // one download at a time

        // Must happen within a few seconds of startForegroundService(): ForegroundServiceDidNotStartInTimeException otherwise.
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(0, 0, indeterminate = true),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)

        val lat = intent!!.getDoubleExtra(EXTRA_LAT, 0.0)
        val lon = intent.getDoubleExtra(EXTRA_LON, 0.0)
        val radiusKm = intent.getDoubleExtra(EXTRA_RADIUS_KM, 10.0)

        job = scope.launch {
            try {
                MapDownloader(applicationContext).run(lat, lon, radiusKm) { done, total ->
                    val now = System.currentTimeMillis()
                    if (now - lastNotify > 500) { lastNotify = now; notify(buildNotification(done, total, false)) }
                }
                stopFgs(); notify(terminalNotification("Map downloaded"))
            } catch (e: CancellationException) {
                stopFgs(); NotificationManagerCompat.from(this@MapDownloadService).cancel(NOTIF_ID)
            } catch (e: Exception) {
                stopFgs(); notify(terminalNotification("Map download failed: ${e.message}"))
            } finally {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15 (API 35): dataSync FGS may run 6 h per 24 h while the app is in the background; we get a few seconds to stop. */
    override fun onTimeout(startId: Int, fgsType: Int) { job?.cancel(); stopSelf() }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun stopFgs() = ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)

    private fun notify(n: Notification) {
        try { NotificationManagerCompat.from(this).notify(NOTIF_ID, n) } catch (_: SecurityException) { /* POST_NOTIFICATIONS denied: FGS still runs */ }
    }

    private fun buildNotification(done: Long, total: Long, indeterminate: Boolean): Notification {
        val cancel = PendingIntent.getService(this, 1,
            Intent(this, MapDownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val max = 1000
        val progress = if (total > 0) (done * max / total).toInt() else 0
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Downloading map")
            .setContentText(if (total > 0) "${Formatter.formatShortFileSize(this, done)} / ${Formatter.formatShortFileSize(this, total)}" else "Preparing…")
            .setProgress(max, progress, indeterminate)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Cancel", cancel)
            .build()
    }

    private fun terminalNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_download)
            .setContentTitle(text).setProgress(0, 0, false).setAutoCancel(true).build()
}
```
### MBTiles writer: WAL + synchronous=NORMAL (API 26 and 28+ paths), batched INSERT OR REPLACE with reused SQLiteStatement, TMS y-flip, metadata upsert, final checkpoint
```
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import android.os.Build
import java.io.Closeable
import java.io.File

class MbtilesWriter private constructor(private val db: SQLiteDatabase) : Closeable {
    companion object {
        private const val FLAGS = SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS

        fun open(file: File): MbtilesWriter {
            file.parentFile?.mkdirs()
            val db = if (Build.VERSION.SDK_INT >= 28) {
                SQLiteDatabase.openDatabase(file, SQLiteDatabase.OpenParams.Builder()
                    .setOpenFlags(FLAGS)
                    .setJournalMode("WAL")        // JOURNAL_MODE_WAL constant is API 33; the string works on 28+
                    .setSynchronousMode("NORMAL") // SYNC_MODE_NORMAL constant is API 33
                    .build())
            } else {
                SQLiteDatabase.openDatabase(file.path, null, FLAGS).also {
                    it.enableWriteAheadLogging()            // NOT execSQL("PRAGMA journal_mode=WAL"): returns a row -> throws on API 26/27
                    it.execSQL("PRAGMA synchronous=NORMAL")  // no result row -> execSQL is fine
                }
            }
            db.execSQL("PRAGMA cache_size=-32768")  // 32 MiB page cache during bulk load (session-only)
            db.execSQL("CREATE TABLE IF NOT EXISTS metadata (name TEXT NOT NULL, value TEXT)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS metadata_name ON metadata (name)")
            db.execSQL("CREATE TABLE IF NOT EXISTS tiles (zoom_level INTEGER NOT NULL, tile_column INTEGER NOT NULL, tile_row INTEGER NOT NULL, tile_data BLOB NOT NULL)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS tile_index ON tiles (zoom_level, tile_column, tile_row)")
            return MbtilesWriter(db)
        }
    }

    private val insertTile: SQLiteStatement =
        db.compileStatement("INSERT OR REPLACE INTO tiles (zoom_level, tile_column, tile_row, tile_data) VALUES (?,?,?,?)")
    private val upsertMeta: SQLiteStatement =
        db.compileStatement("INSERT OR REPLACE INTO metadata (name, value) VALUES (?,?)")
    private var rowsInTx = 0
    private var bytesInTx = 0L

    /** [y] is XYZ (top-left origin); stored as TMS row. [gzipMvt] is stored as-is – MapLibre gunzips. Single writer thread only. */
    fun putTile(z: Int, x: Int, y: Int, gzipMvt: ByteArray) {
        if (rowsInTx == 0) db.beginTransactionNonExclusive()          // IMMEDIATE: readers keep reading under WAL
        insertTile.clearBindings()
        insertTile.bindLong(1, z.toLong())
        insertTile.bindLong(2, x.toLong())
        insertTile.bindLong(3, (1L shl z) - 1 - y)                    // TMS flip per MBTiles spec
        insertTile.bindBlob(4, gzipMvt)
        insertTile.executeInsert()
        rowsInTx++; bytesInTx += gzipMvt.size
        if (rowsInTx >= 1000 || bytesInTx >= (16L shl 20)) commit()  // ~1000 rows or 16 MiB per transaction
    }

    fun commit() {
        if (rowsInTx == 0) return
        db.setTransactionSuccessful(); db.endTransaction()
        rowsInTx = 0; bytesInTx = 0
    }

    fun putMetadata(values: Map<String, String>) {
        commit()
        db.beginTransactionNonExclusive()
        try {
            for ((k, v) in values) { upsertMeta.clearBindings(); upsertMeta.bindString(1, k); upsertMeta.bindString(2, v); upsertMeta.executeInsert() }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun zoomRange(): IntRange? = db.rawQuery("SELECT MIN(zoom_level), MAX(zoom_level) FROM tiles", null).use { c ->
        if (c.moveToFirst() && !c.isNull(0)) c.getInt(0)..c.getInt(1) else null
    }

    override fun close() {
        commit()
        insertTile.close(); upsertMeta.close()
        db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() } // returns a row -> rawQuery
        db.close()
    }
}

// Metadata after a merge (one file per zoom band; example: the z13–15 "10 km" band):
fun writeBandMetadata(w: MbtilesWriter, bandMin: Int, bandMax: Int, bounds: DoubleArray, centerLon: Double, centerLat: Double, vectorLayersJson: String) {
    w.putMetadata(mapOf(
        "name" to "VeloTrack z$bandMin-$bandMax",
        "format" to "pbf",                          // REQUIRED: MapLibre assumes png if absent
        "minzoom" to bandMin.toString(),            // TileJSON zoom range comes from these two rows
        "maxzoom" to bandMax.toString(),
        "bounds" to bounds.joinToString(","),      // exactly 4 numbers (w,s,e,n) or the source errors; ignored for pbf otherwise
        "center" to "$centerLon,$centerLat,$bandMin",
        "type" to "baselayer", "version" to "1",
        "attribution" to "© OpenStreetMap contributors, © Protomaps",
        "json" to vectorLayersJson                  // ONLY {"vector_layers":[...]} – do not copy other PMTiles metadata keys
    ))
}

// File location: no permission needed, deleted on uninstall
fun mapsDir(ctx: android.content.Context): File =
    (ctx.getExternalFilesDir("maps") ?: File(ctx.filesDir, "maps")).apply { mkdirs() }
// Style source URL: "mbtiles:///" + File(mapsDir(ctx), "band_13_15.mbtiles").absolutePath
```
### Web Mercator: lon/lat → tile x/y, circle-radius bounding box (clamped to ±85.0511, antimeridian-aware), and tile range enumeration per zoom
```
import kotlin.math.*

object Mercator {
    const val MAX_LAT = 85.05112878  // atan(sinh(pi)) in degrees

    fun lonToTileX(lon: Double, z: Int): Int {
        val n = 1 shl z
        return floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
    }

    fun latToTileY(lat: Double, z: Int): Int {
        val n = 1 shl z
        val latRad = Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT))
        return floor((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
    }

    data class BBox(val west: Double, val south: Double, val east: Double, val north: Double) // west may be < -180 / east > 180 when it crosses the antimeridian

    /** Bounding box of a circle of [radiusKm] around (lat, lon). dLat = r/111.32, dLon = r/(111.32*cos lat). */
    fun circleBBox(lat: Double, lon: Double, radiusKm: Double): BBox {
        val dLat = radiusKm / 111.32
        val cosLat = cos(Math.toRadians(lat)).coerceAtLeast(1e-9)
        val dLon = radiusKm / (111.32 * cosLat)
        val south = (lat - dLat).coerceIn(-MAX_LAT, MAX_LAT)
        val north = (lat + dLat).coerceIn(-MAX_LAT, MAX_LAT)
        return if (dLon >= 180.0) BBox(-180.0, south, 180.0, north) else BBox(lon - dLon, south, lon + dLon, north)
    }

    /** All (x, y) tiles at [z] covering [b]; returns the x-ranges (1 or 2 when crossing ±180) and the y-range. */
    fun tileRanges(b: BBox, z: Int): Pair<List<IntRange>, IntRange> {
        val n = 1 shl z
        val yRange = latToTileY(b.north, z)..latToTileY(b.south, z)   // y grows southwards
        val xRanges = when {
            b.east - b.west >= 360.0 -> listOf(0 until n)
            b.west < -180.0 -> listOf(lonToTileX(b.west + 360.0, z)..(n - 1), 0..lonToTileX(b.east, z))
            b.east > 180.0 -> listOf(lonToTileX(b.west, z)..(n - 1), 0..lonToTileX(b.east - 360.0, z))
            else -> listOf(lonToTileX(b.west, z)..lonToTileX(b.east, z))
        }
        return xRanges to yRange
    }

    fun tileCount(b: BBox, zooms: IntRange): Long = zooms.sumOf { z ->
        val (xs, ys) = tileRanges(b, z); xs.sumOf { it.count().toLong() } * ys.count()
    }
}

// Radius -> zoom band (each band is its own MBTiles file / style source):
// 10 000 km -> z0..6 (whole planet, ~60 MB per Protomaps docs)   1000 km -> z7..9
//    100 km -> z10..12                                             10 km -> z13..15
```
### PMTiles v3 reader pieces for the extractor: header parse, Hilbert zxy→tileId (verified against reference), varint directory decode, findTile, and range coalescing with an overfetch budget
```
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

data class PmHeader(
    val rootDirOffset: Long, val rootDirLength: Long, val metadataOffset: Long, val metadataLength: Long,
    val leafDirOffset: Long, val leafDirLength: Long, val tileDataOffset: Long, val tileDataLength: Long,
    val internalCompression: Int, val tileCompression: Int, val tileType: Int, val minZoom: Int, val maxZoom: Int
)

fun parseHeader(first16k: ByteArray): PmHeader {
    require(first16k.size >= 127 && String(first16k, 0, 7, Charsets.US_ASCII) == "PMTiles" && first16k[7].toInt() == 3) { "not a PMTiles v3 archive" }
    val b = ByteBuffer.wrap(first16k).order(ByteOrder.LITTLE_ENDIAN)
    return PmHeader(b.getLong(8), b.getLong(16), b.getLong(24), b.getLong(32), b.getLong(40), b.getLong(48), b.getLong(56), b.getLong(64),
        first16k[97].toInt(), first16k[98].toInt(), first16k[99].toInt(), first16k[100].toInt(), first16k[101].toInt())
    // internalCompression / tileCompression: 1=none 2=gzip 3=brotli 4=zstd; tileType 1=MVT. Planet build: gzip/gzip/MVT, z0-15.
}

/** Hilbert TileID exactly as protomaps/PMTiles js/src/index.ts (checked z0-7 exhaustively). */
fun zxyToTileId(z: Int, x: Int, y: Int): Long {
    require(z in 0..26 && x < (1 shl z) && y < (1 shl z))
    var acc = ((1L shl z) * (1L shl z) - 1) / 3
    var tx = x.toLong(); var ty = y.toLong()
    var a = z - 1
    var s = if (a >= 0) 1L shl a else 0L
    while (s > 0) {
        val rx = tx and s; val ry = ty and s
        acc += ((3 * rx) xor ry) * (1L shl a)
        if (ry == 0L) {
            if (rx != 0L) { val nx = s - 1 - ty; val ny = s - 1 - tx; tx = nx; ty = ny } else { val t = tx; tx = ty; ty = t }
        }
        s = s shr 1; a--
    }
    return acc
}

data class Entry(val tileId: Long, var offset: Long, var length: Int, var runLength: Int)

private class VarintReader(val buf: ByteArray) {
    var pos = 0
    fun read(): Long { var shift = 0; var result = 0L
        while (true) { val b = buf[pos++].toLong() and 0xff; result = result or ((b and 0x7f) shl shift); if (b and 0x80 == 0L) return result; shift += 7 } }
}

fun gunzip(data: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }

/** Decode a (already decompressed) directory. */
fun deserializeDirectory(bytes: ByteArray): List<Entry> {
    val r = VarintReader(bytes)
    val n = r.read().toInt()
    val entries = ArrayList<Entry>(n)
    var last = 0L
    repeat(n) { last += r.read(); entries.add(Entry(last, 0, 0, 1)) }
    for (e in entries) e.runLength = r.read().toInt()
    for (e in entries) e.length = r.read().toInt()
    for (i in entries.indices) { val v = r.read(); entries[i].offset = if (v == 0L && i > 0) entries[i - 1].offset + entries[i - 1].length else v - 1 }
    return entries
}

/** Binary search: returns the entry covering [tileId] (a run) or the leaf entry (runLength==0) to descend into, or null. */
fun findTile(entries: List<Entry>, tileId: Long): Entry? {
    var m = 0; var n = entries.size - 1
    while (m <= n) { val k = (n + m) ushr 1; val c = tileId - entries[k].tileId
        if (c > 0) m = k + 1 else if (c < 0) n = k - 1 else return entries[k] }
    if (n >= 0) { val e = entries[n]; if (e.runLength == 0 || tileId - e.tileId < e.runLength) return e }
    return null
}

/**
 * Coalesce sorted, non-overlapping absolute byte ranges: merge neighbours while the wasted gap stays within [overfetch]
 * of the payload and no merged range exceeds [maxRange] (keeps per-request RAM bounded). Mirrors go-pmtiles mergeRanges in spirit.
 */
fun coalesce(sorted: List<LongRange>, overfetch: Double = 0.05, maxRange: Long = 8L shl 20): List<LongRange> {
    if (sorted.isEmpty()) return emptyList()
    var budget = (sorted.sumOf { it.last - it.first + 1 } * overfetch).toLong()
    val out = ArrayList<LongRange>()
    var cur = sorted[0]
    for (i in 1 until sorted.size) {
        val nx = sorted[i]; val gap = nx.first - cur.last - 1
        if (gap <= budget && nx.last - cur.first + 1 <= maxRange) { budget -= gap; cur = cur.first..nx.last } else { out.add(cur); cur = nx }
    }
    out.add(cur)
    return out
}

// Extraction plan (per band file):
// 1. GET bytes 0-16383 -> header (+ root dir at 127..127+rootDirLength, gunzip, deserializeDirectory).
// 2. needed = sortedSet of zxyToTileId(z,x,y) for all tiles from Mercator.tileRanges over the band's zooms.
// 3. For each root entry: if runLength>0 it is a tile (match against needed); if runLength==0 it is a leaf covering
//    [entry.tileId, nextEntry.tileId) -> fetch only leaves that intersect `needed` (absolute range leafDirOffset+offset .. +length-1), gunzip, decode.
// 4. For each needed id: findTile -> (tileDataOffset+offset, length); group ids by identical (offset,length) so shared blobs (runs, ocean) download once.
// 5. coalesce() the sorted blob ranges -> RangeClient.fetchAll(); slice blobs out of each chunk and hand (z,x,y,bytes) to a single MbtilesWriter thread via a Channel.
// 6. Metadata JSON (metadataOffset/Length, gunzip if internalCompression==gzip) -> copy only its "vector_layers" into the MBTiles `json` row.
```

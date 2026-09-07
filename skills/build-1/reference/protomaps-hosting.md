# protomaps-hosting

## Recommendation
Hosting/policy: resolve the key from builds.json at tap time (last element), download from https://build.protomaps.com/<key> with coalesced Range requests, pin the ETag for the session and restart on mismatch; keep the base URL configurable because Protomaps discourages hotlinking and publishes no rate limit or SLA — plan to mirror to your own R2/S3 later. Ignore the Source Cooperative mirror as a fallback (5 months stale). Show '© OpenStreetMap' linking to openstreetmap.org/copyright on the map (collapsible is fine per OSMF); crediting Protomaps is courtesy.

Detail mapping (from Roads/Buildings generation code + measured sizes, build 20260907): 10 km radius → z15 (individual buildings, all paths, POIs; ~25-35 MB per zoom level in a dense city, ~1 MB rural; ~50 MB cumulative). 100 km radius → z14 (cycleways from z13, minor-road/path names from z14, merged buildings; ~75-100 MB for z14 alone, ~195 MB cumulative) or z13 as a cheaper option (~45 MB / ~95 MB cumulative, no street names). 1000 km radius → z10 (motorway…tertiary only; ~313 MB for z10 alone, ~500 MB cumulative) or z9 (~125 MB) if you want <300 MB. 10000 km radius = planet → z7 (144 MB; z0-7 total 189 MB; z0-6 only 45 MB). France bbox → z10 ≈ 133 MB (z0-10 ≈ 220 MB); z11 ≈ 266 MB (z0-11 ≈ 490 MB); z11 adds nothing structurally over z10 (minor roads/paths only start at z12, ≈ 670 MB), so pick z10 for France unless you want z12 at ~1.2 GB. Download z0-7 once as a shared world base and dedupe across regions.

Overzoom/storage: MapLibre overzooms only past the source maxzoom; a missing tile inside the zoom range renders as an empty (renderable) tile with no parent fallback in MapLibre Native, so a single MBTiles with sparse deeper zooms will show blank areas. Prefer one MBTiles per region (metadata maxzoom + bounds per file) added as separate vector sources with duplicated style layers, or keep one file with maxzoom = lowest regional detail. Write tiles TMS-flipped, keep the gzip blobs as-is, copy vector_layers into metadata 'json'. For the extractor, port the ~150 lines (header, varint directory decode, Hilbert tile id, leaf cache) from protomaps/PMTiles (BSD-3) rather than adding a dependency; if you want a library, simonpoole/pmtiles-reader (MIT, ch.poole.geo.pmtiles-reader:Reader:0.3.7) is Android-ready and takes an OkHttp FileChannel wrapper, and MapLibre Native's pmtiles_file_source.cpp shows the cache/decompress handling. Coalesce contiguous tile ranges (archive is clustered) so France is hundreds of requests, not tens of thousands.

## Facts
- [verified] builds.json is a JSON array (61 entries on 2026-09-07) of objects {key, size, md5sum, b3sum (present from 2025 builds on), uploaded (ISO-8601), version}. Entries are in ascending upload order; the latest build is the last element: key "20260907.pmtiles", size 137,838,277,849 bytes, version "4.15.2", uploaded 2026-09-07T08:50:51Z. Older entries (one per patch version, e.g. 20230918 v0.0.0 … 20250317 v4.6.x) are retained, matching the documented policy.
  src: https://build-metadata.protomaps.dev/builds.json
- [verified] The actual object URL is https://build.protomaps.com/<key> (e.g. https://build.protomaps.com/20260907.pmtiles). It is served by Cloudflare, returns accept-ranges: bytes, content-length 137838277849, a multipart ETag "9bb9b5346f56a145e618d4320a59d59c-514", and answers Range: bytes=0-126 with HTTP 206 + content-range, with no authentication. It does NOT send Access-Control-Allow-Origin (irrelevant for a native Android client).
  src: https://build.protomaps.com/20260907.pmtiles
- [verified] Docs (basemaps/downloads): the daily builds bucket keeps "all builds for the past week" plus "the latest build for each patch version (e.g. 4.3.0)"; "URLs may change" and "hotlinking to these downloads are discouraged"; users should "copy the tileset to your own Cloud Storage". Planet is "roughly 120 gigabytes" for z0-15 (actual today: 137.8 GB). "each additional zoom level roughly doubles the size of the file". License: Open Database License, Produced Work, OpenStreetMap attribution required.
  src: https://docs.protomaps.com/basemaps/downloads
- [verified] No rate limit, fair-use quota, or terms of service for build.protomaps.com range requests is published anywhere checked: docs downloads page, docs security-privacy page, maps.protomaps.com/builds (JS-rendered UI over builds.json), source.coop README. PMTiles Discussion #473 (bdon) concerns users protecting their OWN hosted archives from bot egress, not Protomaps' hosting policy.
  src: https://github.com/protomaps/PMTiles/discussions/473
- [verified] Source Cooperative mirror public HTTPS URL pattern: https://data.source.coop/protomaps/openstreetmap/<file>. Files: v4.pmtiles, tilezen.pmtiles, tiles/v3.pmtiles, tiles/v2/, README.md. https://data.source.coop/protomaps/openstreetmap/v4.pmtiles answers Range requests with 206, accept-ranges: bytes, Access-Control-Allow-Origin: *, no auth. The S3 REST form https://s3.us-west-2.amazonaws.com/us-west-2.opendata.source.coop/protomaps/openstreetmap/v4.pmtiles also returns 206 unauthenticated. The virtual-hosted form https://us-west-2.opendata.source.coop/... could not be reached from this sandbox (proxy 502) — unverified.
  src: https://source.coop/protomaps/openstreetmap
- [verified] The Source Cooperative mirror is STALE despite the README saying tiles/v3.pmtiles is "Mirrored daily": v4.pmtiles has last-modified 2026-03-31, content-length 134,812,420,554, PMTiles metadata version 4.14.3, OSM replication time 2026-03-31T04:00:00Z (vs daily build 4.15.2 / 2026-09-07). tiles/v3.pmtiles has last-modified 2024-08-30 (basemap v3 schema, incompatible with v4 styles). README also says "We don't recommend cross-origin hotlinking directly to source.coop URLs" and data are "Produced Works of the Open Database License (ODbL)".
  src: https://data.source.coop/protomaps/openstreetmap/README.md
- [verified] Daily planet PMTiles header (20260907): version 3, root dir 15,561 bytes (gzip), metadata 1,179 bytes, leaf directories total 351,592,152 bytes in 2,917 leaves (avg ~120 KB gzip each), tile data 137,486,668,134 bytes, addressed tiles 1,431,655,765 (= every z0-15 tile), tile entries 177,546,433, tile contents 135,652,197, clustered=1, internal_compression=2 (gzip), tile_compression=2 (gzip), tile_type=1 (MVT), minzoom 0, maxzoom 15, bounds -180,-85.0511287,180,85.0511287. Root directory contains only 2,917 leaf pointers (no direct tile entries), so every tile lookup costs header+root (cacheable) + 1 leaf + 1 tile range.
  src: https://build.protomaps.com/20260907.pmtiles
- [verified] Planet metadata JSON: name "Protomaps Basemap", type "baselayer", version "4.15.2", attribution '<a href="https://www.openstreetmap.org/copyright" target="_blank">&copy; OpenStreetMap</a>', vector_layers with minzoom/maxzoom: boundaries 0-15, buildings 11-15, earth 0-15, landcover 0-7, landuse 2-15, places 1-15, pois 5-15, roads 3-15, water 0-15. planetiler:buildtime 2026-03-28 (software), osm replication time 2026-09-07T04:00:00Z (data).
  src: https://build.protomaps.com/20260907.pmtiles
- [verified] MEASURED compressed tile bytes (sum of directory entry lengths, i.e. exactly what a range-request extractor downloads), build 20260907, per zoom level only (not cumulative). 20 km square (10 km radius): Paris z13 3.96 MB/49 tiles, z14 10.3 MB/182, z15 34.0 MB/676 (avg 50 KB, max 164 KB); Berlin centre z15 24.1 MB/784; rural France (1.0E,46.5N) z14 0.57 MB, z15 1.07 MB (avg 1.8 KB). 200 km square: Paris z11 11.3 MB, z12 24.7 MB, z13 46.4 MB/4032 tiles, z14 100.8 MB/15876 tiles; rural z13 40.4 MB, z14 74.4 MB. 2000 km square centred Paris: z8 40.6 MB, z9 125.4 MB, z10 313.1 MB/6320 tiles. France bbox (-5.5,41.3,9.7,51.2): z9 58.5 MB, z10 133.3 MB/1848, z11 266.3 MB/7304, z12 670.6 MB/28710. Whole planet: z0 0.07, z1 0.21, z2 0.59, z3 1.57, z4 3.59, z5 9.02, z6 30.1, z7 143.7 MB (cumulative z0-6 = 45.1 MB; z0-7 = 188.8 MB). 20000 km square clamped to ±85°: z6 26.8 MB, z7 133.0 MB. Berlin docs bbox z0-15 cumulative = 80.5 MB (docs/blog figure ~84 MB — consistent).
  src: https://build.protomaps.com/20260907.pmtiles
- [verified] Roads.java minzoom rules (basemap v4): kind highway (motorway) minzoom 3, name 11, shield 7; trunk 6 (name 12); primary 7 (name 12); secondary 9 (name 12); tertiary 9 (name 13); minor_road (residential, unclassified, road, raceway) 12; minor_road service 13; path (pedestrian, track, corridor) 12; path with kind_detail path/cycleway/bridleway/footway/steps 13; sidewalk/crossing/corridor 14; default fallback rule minzoom 14 / minzoomName 14 / minzoomShield 12 (so minor road & path NAMES are in the data from z14); rail 11 (subway/tram/light_rail/funicular/monorail/narrow_gauge 14); ferry 11; aerialway 11; pier 13; runway 9; taxiway 10.
  src: https://raw.githubusercontent.com/protomaps/basemaps/main/tiles/src/main/java/com/protomaps/basemap/layers/Roads.java
- [verified] Buildings.java: buildings minZoom = 11 (building parts 14), zoom range up to 15; at z11-14 buildings are merged with FeatureMerge.mergeNearbyPolygons and area-filtered; at z15 individual buildings are emitted unmerged; address points minzoom 15. Places.java: country 5-8, region 8-11, city 7, town 7-9, village 10, hamlet 11, locality default 11-12. Pois.java: default minzoom 15, some kinds 13/14, hospital 12, national_park/aerodrome 11, bus_stop 17 (beyond tileset max → effectively absent).
  src: https://raw.githubusercontent.com/protomaps/basemaps/main/tiles/src/main/java/com/protomaps/basemap/layers/Buildings.java
- [verified] Protomaps official style (styles/src/base_layers.ts) layer minzooms: roads_labels_minor minzoom 15; roads_labels_major 11; roads_oneway 16; address_label 18; roads_link / roads_minor_service_casing / roads_taxiway 13; all roads_bridges_* 12; roads_tunnels_* 13-14; water_stream 14; water_river 9; roads_major_casing_early maxzoom 12 / _late minzoom 12. So minor street names are only drawn at display zoom ≥15 (from data present in z14+ tiles); with z14 tiles overzoomed, names still appear at z15+.
  src: https://raw.githubusercontent.com/protomaps/basemaps/main/styles/src/base_layers.ts
- [verified] MapLibre style spec: source maxzoom = "Maximum zoom level for which tiles are available, as in the TileJSON spec. Data from tiles at the maxzoom are used when displaying the map at higher zoom levels." (default 22). bounds: "When this property is included in a source, no tiles outside of the given bounds are requested by MapLibre."
  src: https://maplibre.org/maplibre-style-spec/sources/
- [verified] Planetiler discussion #506: bdon — "zoom 15 is sufficient for detailed mapping of typical street-level datasets when combined with overzooming", "anywhere from zoom 16-20 should look fine as the renderer is re-using the zoom 15 tile", "the source object in the map style should be set to 15". MapLibre GL JS issue #2507 documents degradation at extreme overzoom (precision loss with 8192 extent, label duplication) — relevant only many levels past maxzoom.
  src: https://github.com/onthegomap/planetiler/discussions/506
- [verified] MapLibre Native mbtiles_file_source.cpp (platform/default/src/mln/storage): builds TileJSON from the metadata table (copies key "json" i.e. vector_layers; scheme forced "xyz"; if minzoom/maxzoom missing it runs SELECT MIN(zoom_level),MAX(zoom_level) FROM tiles; parses bounds "w,s,e,n" and emits bounds + center). Tile SQL: SELECT tile_data FROM tiles WHERE zoom_level=z AND tile_column=x AND tile_row=(2^z-1-y) — i.e. rows are TMS-flipped. If util::is_compressed(tile_data) it gunzips, so gzip blobs from PMTiles can be inserted verbatim. A missing row yields Response{noContent=true} with NO error.
  src: https://raw.githubusercontent.com/maplibre/maplibre-native/main/platform/default/src/mln/storage/mbtiles_file_source.cpp
- [verified] MapLibre Native does NOT fall back to parent tiles for a missing tile inside the source zoom range: TileLoader::loadedData treats a NotFound error like success and calls tile.setData(res.noContent ? nullptr : res.data); GeometryTile::onLayout then sets loaded=true, renderable=true even for empty data; update_renderables.hpp only ascends to parent tiles when the ideal tile is !isRenderable(). Overzoom happens only beyond the source's zoomRange.max (tile_pyramid.cpp: idealZoom = min(zoomRange.max, overscaledZoom)). Consequence: a single merged MBTiles with metadata maxzoom=15 renders BLANK in areas that only have z10 data when viewed at z11-15. (PMTiles Discussion #321 confirms the equivalent GL JS behaviour; the errorOnMissingTile 404 trick from PMTiles JS 4.1.0 helps GL JS only, not Native.)
  src: https://raw.githubusercontent.com/maplibre/maplibre-native/main/src/mln/algorithm/update_renderables.hpp
- [verified] MapLibre Android supports PMTiles natively: docs say "Starting MapLibre Android 11.7.0, PMTiles archives are supported" (CHANGELOG lists 'Add PMTiles support (#2882)' under 11.8.0); URL forms pmtiles://https://... and pmtiles://file://...; "PMTiles sources do not support offline pack downloads or caching" (CHANGELOG 13.3.0 later added an ambient cache for PMTiles, #4290). pmtiles_file_source.cpp uses vendor/PMTiles (pmtiles.hpp, BSD-3), caches header/metadata/directories (MAX_DIRECTORY_CACHE_ENTRIES=100), accepts only gzip or none compression, forces XYZ scheme (11.8.8, #3403), and returns noContent for missing tiles.
  src: https://maplibre.org/maplibre-native/android/examples/data/PMTiles/
- [verified] PMTiles v3 spec: 127-byte header; header + compressed root directory MUST NOT exceed 16,384 bytes (root ≤ 16,257 bytes) so clients can fetch it in one request; directories = varint count, then delta-encoded TileIDs, RunLengths, Lengths, Offsets (0 = contiguous with previous, else offset+1); RunLength 0 = leaf directory entry; tile offsets are relative to the tile data section, leaf offsets relative to the leaf section; lengths are compressed sizes; clustered = tile data ordered by TileID so adjacent Hilbert tiles are byte-contiguous; more than one level of leaf directories is discouraged; spec is CC0/public domain, reference implementations BSD-3 (Protomaps LLC).
  src: https://raw.githubusercontent.com/protomaps/PMTiles/main/spec/v3/spec.md
- [verified] pmtiles CLI extract flags: --bbox=MIN_LON,MIN_LAT,MAX_LON,MAX_LAT, --region=GeoJSON, --maxzoom, --minzoom (warned: >0 'may require many more requests'), --download-threads, --overfetch (default 0.1 = 10% extra bytes to batch small ranges); source must be clustered. go-pmtiles issue #68 example: US+Mexico z0-15 extract transferred 18 GB for a 17 GB archive in 9m24s.
  src: https://docs.protomaps.com/pmtiles/cli
- [verified] simonpoole/pmtiles-reader: MIT-licensed (LICENCE.txt, Copyright 2023 Simon Poole), Maven Central ch.poole.geo.pmtiles-reader:Reader:0.3.7, Android 4.1+ compatible, PMTiles v3, API Reader(File|FileChannel).getTile(z,x,y), setLeafDirectoryCacheSize (default 20), getTileCompression(); it does NOT decompress tiles; supports gzip/zstd directories; remote access via a FileChannel wrapper (sample HttpUrlConnectionChannel included; Vespucci ships an OkHttp variant OkHttpFileChannel.java); ETag-based SourceChangedException detects the file changing underneath. Last push 2025-12-12. Source files: Reader.java, Hilbert.java, VarInt.java, HttpUrlConnectionChannel.java, UrlFileChannel.java.
  src: https://github.com/simonpoole/pmtiles-reader
- [verified] tileverse-io/tileverse (contains tileverse-pmtiles) is Apache-2.0 but targets Java 17+ (heavier for Android). protomaps/PMTiles JS (BSD-3) FetchSource.getBytes: sends header range: bytes=offset-(offset+length-1); throws if a 200 with content-length > requested is returned (server lacks byte serving); handles 416 at offset 0 by reading total length from Content-Range; ignores weak ETags (W/); detects ETag change between requests to invalidate cached directories. zxyToTileId is the Hilbert-curve id: acc = (4^z - 1)/3 + d.
  src: https://github.com/protomaps/PMTiles/blob/main/js/src/index.ts
- [verified] OSMF Attribution Guidelines: credit must be to 'OpenStreetMap' ("© OpenStreetMap" / "© OpenStreetMap contributors" acceptable), typically in a corner of the map, may be collapsible/dismissable but licence info must remain reachable (info button/menu), and must make clear data is under ODbL, normally by linking to openstreetmap.org/copyright. Protomaps basemaps repo: code BSD-3 (Protomaps LLC, Kelso Cartography), styles CC0, Tilezen schema MIT; docs' MapLibre example uses attribution '<a href="https://protomaps.com">Protomaps</a> © <a href="https://openstreetmap.org">OpenStreetMap</a>' (Protomaps credit is courtesy, not a legal requirement).
  src: https://osmfoundation.org/wiki/Licence/Attribution_Guidelines
- [verified] Docs Getting Started states a z0-6 planet subset is "~60 MB"; my measurement of compressed z0-6 tile bytes on 20260907 is 45.1 MB (the 60 MB figure likely includes an older build or PMTiles overhead). Per-tile averages measured: z15 dense city 30-50 KB, rural 2 KB; z14 city 57 KB, mixed 200 km region 5-6 KB; z13 ~11 KB; z12 ~24 KB; z11 ~37-39 KB; z10 50-72 KB; z9 76-121 KB; z8 ~92 KB; z7 land ~17 KB (max single tile 486 KB at z7). Zoom-to-zoom growth measured 2.0-3.3x (docs: 'roughly doubles').
  src: https://docs.protomaps.com/guide/getting-started

## Gotchas
- Do not derive the download URL from the docs' 'maps.protomaps.com/builds' text — that is the JS UI. The object host is build.protomaps.com/<key>; always resolve <key> from https://build-metadata.protomaps.dev/builds.json (last element / max 'uploaded') at the start of a download and pin it for that session.
- Daily builds older than 7 days are deleted (only latest-per-patch-version survive). A long or resumed download must re-check the key exists (HEAD) and compare ETag on every 206 response; on mismatch (file replaced) abort and restart with a new key — cached directories from the old file are invalid.
- Protomaps explicitly discourages hotlinking and says URLs may change; there is no published rate limit or fair-use policy for end-user range requests. Fetching a few hundred MB per user tap in batched contiguous ranges is within what their own CLI does, but the project's stated recommendation is to copy the archive to your own storage (R2/S3). Build in a configurable base URL and honour HTTP 429/403 with backoff.
- The Source Cooperative mirror is not a safe fallback: v4.pmtiles is 5 months stale (4.14.3, OSM 2026-03-31) despite the README's 'mirrored daily' claim; tiles/v3.pmtiles is the incompatible v3 schema from 2024. Verify freshness via the metadata JSON (planetiler:osm:osmosisreplicationtime) if you use it.
- Root directory has ONLY leaf pointers (2,917 leaves, ~120 KB gzip each). Cache header+root once per session, cache leaf dirs by offset; a 200 km bbox at z14 touches only 1-3 leaves thanks to Hilbert locality, but a 2000 km box or France touches more — budget ~0.3-3 MB of directory traffic per download.
- Because the archive is clustered, Hilbert-adjacent tiles are byte-contiguous: sort the needed tile entries by offset and coalesce ranges with a gap tolerance (pmtiles CLI default overfetch 10%). Naively issuing one Range request per tile means ~38k requests for France z0-12 — coalesce to hundreds instead. Also dedupe identical offsets (run-length/ocean tiles) — measured savings are tiny (<0.5%) except at z≤7.
- MBTiles conventions MapLibre Native actually reads: tile_row is TMS-flipped (row = 2^z - 1 - y); tile_data may stay gzip-compressed (MapLibre gunzips via util::is_compressed); metadata needs format=pbf, json={"vector_layers":[...]} copied from the PMTiles metadata, plus minzoom/maxzoom/bounds (bounds as 'w,s,e,n' string, exactly 4 values or MapLibre errors). If minzoom/maxzoom are absent MapLibre computes them from SELECT MIN/MAX(zoom_level).
- Sparse merged MBTiles does NOT overzoom per-area in MapLibre Native: a missing tile inside [minzoom,maxzoom] is delivered as noContent → an empty but 'renderable' tile → the parent is evicted and the area renders blank (verified in tile_loader_impl.hpp, geometry_tile.cpp, update_renderables.hpp). The PMTiles JS 'errorOnMissingTile' 404 trick only helps MapLibre GL JS. Only zoom > source maxzoom triggers overzoom (tile_pyramid.cpp).
- Workable designs for mixed detail: (a) one MBTiles file per downloaded region, each with its own metadata maxzoom + bounds, added as separate VectorSources (MapLibre skips tile requests outside 'bounds'), with style layers duplicated per source — overlapping regions will double-draw, so clip/merge region bboxes; (b) a single file whose metadata maxzoom is set to the LOWEST detail among regions (uniform but loses detail); (c) accept blank areas. Do not set maxzoom=15 on a file containing z10-only regions.
- Overzoom quality: z15 tiles are the planet max and look fine to ~z20 (bdon); z14 → z15/16 and z13 → z14/15 are also fine; z10 overzoomed to z15 (5 levels) shows only motorway/trunk/primary/secondary/tertiary, no residential streets, paths or buildings — acceptable for route overview, not for navigation.
- Cycling detail thresholds in the data: paths/tracks/pedestrian from z12, cycleways/footways/bridleways/steps only from z13, service roads z13, sidewalks/crossings z14, minor-road and path NAMES from z14, individual (unmerged) buildings and most POIs only at z15. A 100 km download at z13 shows cycleways but no street names; z14 gives names and merged buildings at ~2.2x the bytes.
- MapLibre Android ≥11.8 can open PMTiles directly (pmtiles://file:///path); the app's MBTiles route is still the right call because merging multiple extracts into one clustered PMTiles requires rewriting/sorting the whole archive, whereas SQLite INSERT OR REPLACE is incremental. If you ever switch, note MapLibre's PMTiles source only accepts gzip/none compression.
- The Android MapLibre PMTiles docs say 11.7.0 but the CHANGELOG puts the PR (#2882) under 11.8.0 — minor discrepancy; both are below the app's 13.6.0.
- Measured sizes are for the target zoom only. Cumulative downloads (z0..Zmax for the bbox) add roughly 40-60% on top of the max-zoom level for dense areas (e.g. Paris 20 km z0-15 ≈ 34 + 10.3 + 4 + ~3 ≈ 51 MB; Paris 200 km z0-14 ≈ 195 MB; z0-13 ≈ 95 MB; France z0-10 ≈ 220 MB, z0-11 ≈ 490 MB, z0-12 ≈ 1.16 GB; 2000 km z0-10 ≈ 500 MB; planet z0-7 = 189 MB, z0-6 = 45 MB). Low zooms z0-7 (~190 MB) are needed once for the whole world and should be a separate one-time 'base' download, deduplicated across regions.
- Region size choice: 200 km at z14 is ~100 MB in dense Europe and ~75 MB rural — z14 is affordable for the 100 km radius tier; z13 (~45 MB) if you want a cheaper default. 2000 km at z10 is ~313 MB just for z10; z9 (~125 MB) is the cheaper tier. 10000 km radius is effectively the planet: z7 (144 MB) is fine, z8 would be ~700 MB+ (not measured, extrapolated 5x from z7).

## Snippets
### Verified PMTiles v3 header layout (127 bytes, little-endian) — offsets used to read the planet header successfully
```
// bytes 0-6 magic "PMTiles", byte 7 version (=3)
// 8 rootDirOffset u64, 16 rootDirLength u64, 24 metadataOffset u64, 32 metadataLength u64
// 40 leafDirsOffset u64, 48 leafDirsLength u64, 56 tileDataOffset u64, 64 tileDataLength u64
// 72 numAddressedTiles u64, 80 numTileEntries u64, 88 numTileContents u64
// 96 clustered u8 (1), 97 internalCompression u8 (2=gzip), 98 tileCompression u8 (2=gzip), 99 tileType u8 (1=MVT)
// 100 minZoom u8, 101 maxZoom u8, 102..117 minLon,minLat,maxLon,maxLat i32 (deg*1e7), 118 centerZoom u8, 119..126 centerLon,centerLat i32
val bb = ByteBuffer.wrap(header127).order(ByteOrder.LITTLE_ENDIAN)
require(String(header127, 0, 7, Charsets.US_ASCII) == "PMTiles" && header127[7].toInt() == 3)
val rootOff = bb.getLong(8); val rootLen = bb.getLong(16)
val metaOff = bb.getLong(24); val metaLen = bb.getLong(32)
val leafOff = bb.getLong(40); val tileOff = bb.getLong(56)
val internalComp = header127[97].toInt(); val tileComp = header127[98].toInt()
val maxZoom = header127[101].toInt() and 0xff
// Range request: Range: bytes=<off>-<off+len-1>  ; expect HTTP 206 and compare ETag across requests
```
### Hilbert ZXY -> PMTiles TileID, ported from protomaps/PMTiles js/src/index.ts and validated against spec vectors (0,0,0)->0 (1,0,0)->1 (1,0,1)->2 (1,1,1)->3 (1,1,0)->4 (2,0,0)->5 (12,3423,1763)->19078479
```
fun zxyToTileId(z: Int, x: Int, y: Int): Long {
    require(z <= 26)
    var acc = ((1L shl z) * (1L shl z) - 1) / 3
    var tx = x.toLong(); var ty = y.toLong()
    var s = if (z > 0) 1L shl (z - 1) else 0L
    while (s > 0) {
        val rx = if (tx and s != 0L) 1L else 0L
        val ry = if (ty and s != 0L) 1L else 0L
        acc += ((3 * rx) xor ry) * s * s
        if (ry == 0L) {
            if (rx == 1L) { tx = s - 1 - tx; ty = s - 1 - ty }
            val t = tx; tx = ty; ty = t
        }
        s = s shr 1
    }
    return acc
}
```
### Directory decoding per spec §4.2 (used successfully on the planet root + leaf directories after gunzip)
```
data class Entry(val tileId: Long, val offset: Long, val length: Int, val runLength: Int)
fun decodeDirectory(gunzipped: ByteArray): List<Entry> {
    val r = VarIntReader(gunzipped)
    val n = r.readVarInt().toInt()
    val ids = LongArray(n); var last = 0L
    for (i in 0 until n) { last += r.readVarInt(); ids[i] = last }
    val rl = IntArray(n) { r.readVarInt().toInt() }
    val len = IntArray(n) { r.readVarInt().toInt() }
    val off = LongArray(n)
    for (i in 0 until n) { val v = r.readVarInt(); off[i] = if (v == 0L && i > 0) off[i-1] + len[i-1] else v - 1 }
    return List(n) { Entry(ids[it], off[it], len[it], rl[it]) }
}
// lookup: binary-search largest tileId <= id; if runLength>0 and id < tileId+runLength -> tile at tileDataOffset+offset (length bytes, gzip)
// if runLength==0 -> leaf directory at leafDirsOffset+offset (length bytes, gzip) then repeat search inside it
```
### MBTiles schema/conventions that MapLibre Native's mbtiles_file_source.cpp actually reads (TMS row flip, gzip blobs OK, metadata keys)
```
CREATE TABLE IF NOT EXISTS metadata (name TEXT, value TEXT);
CREATE TABLE IF NOT EXISTS tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB);
CREATE UNIQUE INDEX IF NOT EXISTS tile_index ON tiles (zoom_level, tile_column, tile_row);
-- metadata rows (json copied verbatim from the PMTiles metadata's vector_layers):
INSERT OR REPLACE INTO metadata VALUES ('name','Protomaps Basemap'),('format','pbf'),('type','baselayer'),
 ('minzoom','0'),('maxzoom','14'),('bounds','1.0,48.0,3.7,49.7'),
 ('attribution','<a href="https://www.openstreetmap.org/copyright" target="_blank">&copy; OpenStreetMap</a>'),
 ('json','{"vector_layers":[...from PMTiles metadata...]}');
-- tile insert: PMTiles gives XYZ (y down); MBTiles/MapLibre expects TMS row
-- tile_row = (1 shl z) - 1 - y ; tile_data = raw gzip bytes from the archive (MapLibre gunzips itself)
INSERT OR REPLACE INTO tiles VALUES (?, ?, ((1<<?) - 1 - ?), ?);
```
### Latest-build resolution and range probe (verified responses 2026-09-07)
```
# builds.json -> pick last element (or max 'uploaded')
curl -s https://build-metadata.protomaps.dev/builds.json | python3 -c "import json,sys; b=json.load(sys.stdin)[-1]; print(b['key'], b['size'], b['version'])"
# -> 20260907.pmtiles 137838277849 4.15.2
curl -sI -H 'Range: bytes=0-126' https://build.protomaps.com/20260907.pmtiles
# HTTP/2 206, accept-ranges: bytes, content-range: bytes 0-126/137838277849, etag: "9bb9b5346f56a145e618d4320a59d59c-514"
# Source Cooperative (stale, v4.14.3):
curl -sI -H 'Range: bytes=0-126' https://data.source.coop/protomaps/openstreetmap/v4.pmtiles   # 206, last-modified 2026-03-31
```

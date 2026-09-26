# pmtiles-format

## Recommendation
Re-implement go-pmtiles' Extract almost verbatim, but stream into SQLite instead of writing a new PMTiles: (1) one Range GET for bytes 0-16383 -> parse the 127-byte header (verify magic/version 3/clustered/tile_type 1) and decode the gzip root directory (planet: 2917 leaf pointers, 15.5 KB); (2) build the wanted TreeSet<Long> of Hilbert ids for the bbox at every zoom from header.minZoom (0) to your chosen maxzoom (this equals `pmtiles extract --bbox --maxzoom`; do not use minzoom>0, overview tiles are tiny and needed by the style); (3) run relevantEntries on the root, fetch the intersecting leaves (10 km -> 8 leaves, ~1 MB, contiguous so 1-3 requests), run relevantEntries on each leaf, sort by tile id; (4) dedupe by source offset, sort blobs by source offset, coalesce with overfetch ~0.05-0.2 and a 8-16 MB chunk cap; (5) download chunks with 2-4 parallel connections, checking 206 + Content-Length + stable strong ETag on every response, and stream each chunk through consumeChunk into `INSERT OR REPLACE INTO tiles` inside transactions, writing tile_row = 2^z-1-y and the gzip blob untouched (MapLibre Native's mbtiles reader auto-detects 1f8b and decompresses; MBTiles 'pbf' is defined as gzip MVT); (6) write metadata rows name/format=pbf/minzoom/maxzoom/bounds/center/attribution/description/type/version plus a 'json' row containing vector_layers, recomputing bounds/zooms as the union after each merge. Show the user the exact byte total from the dry-run stage (directories only, ~1-2 MB traffic) before starting the tile phase; the verified 10 km @ z15 case is 780 tiles / 14.05 MB / 36 requests at 5% overfetch. Suggested detail per radius from tile counts: 10 km -> z15 (780 tiles), 100 km -> z12 (1173 tiles; z13 is ~4.4k; z15 is 67k tiles and likely hundreds of MB to GBs), 1000 km -> z10 (6956 tiles), 10000 km -> z7 (9893 tiles) or z8 (39k). Pin one dated URL per download session (probe yesterday's/today's YYYYMMDD with HEAD; files older than ~7 days 404), keep the base URL remotely configurable because Protomaps discourages hotlinking build.protomaps.com and says URLs may change, and add OpenStreetMap attribution in the UI (ODbL). Ship the listed test vectors (Hilbert ids, varints, findTile, relevantEntries, mergeRanges) as unit tests; the z==0 shift guard and the two offset bases (leaf vs tile data) are the two places a Kotlin port most easily goes wrong.

## Facts
- [verified] PMTiles v3 header is exactly 127 bytes, all little-endian: [0..6] magic 'PMTiles' (50 4D 54 69 6C 65 73); [7] version u8 = 3; [8] root_dir_offset u64; [16] root_dir_length u64; [24] metadata_offset u64; [32] metadata_length u64; [40] leaf_dirs_offset u64; [48] leaf_dirs_length u64; [56] tile_data_offset u64; [64] tile_data_length u64; [72] num_addressed_tiles u64; [80] num_tile_entries u64; [88] num_tile_contents u64; [96] clustered u8 (0/1); [97] internal_compression u8; [98] tile_compression u8; [99] tile_type u8; [100] min_zoom u8; [101] max_zoom u8; [102] min_lon i32 e7; [106] min_lat i32 e7; [110] max_lon i32 e7; [114] max_lat i32 e7; [118] center_zoom u8; [119] center_lon i32 e7; [123] center_lat i32 e7. All offsets are absolute from byte 0 of the archive. Positions are value*10_000_000 stored as int32.
  src: https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md
- [verified] Compression enum: 0 Unknown, 1 None, 2 gzip, 3 brotli, 4 zstd. TileType enum: 0 Unknown, 1 MVT, 2 PNG, 3 JPEG, 4 WebP, 5 AVIF, 6 MapLibre Vector Tile (MLT). internal_compression applies to root dir, metadata and every leaf directory (each leaf compressed individually); tile_compression applies to every tile blob. Clustered=1 means tile blobs are ordered by TileID: offsets are either contiguous (prev.offset+prev.length) or refer to a LESSER offset (dedupe); first tile entry has offset 0. num_addressed_tiles = tiles before run-length encoding, num_tile_entries = entries with run_length>0, num_tile_contents = unique blobs; 0 means unknown. The header + compressed root directory MUST fit in the first 16384 bytes (root max 16257 bytes compressed).
  src: https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md
- [verified] Directory encoding (after decompression): varint n; n varint tile-id DELTAS (cumulative sum); n varint run_lengths; n varint lengths (each > 0); n varint offsets where value 0 (for i>0) means offset = prev.offset+prev.length, otherwise offset = value-1. Varints are protobuf-style unsigned LEB128 (7 bits per byte, LSB first, MSB continuation bit), up to 64-bit. run_length == 0 marks a LEAF DIRECTORY entry whose offset is relative to leaf_dirs_offset; run_length >= 1 is a tile entry (offset relative to tile_data_offset) valid for tile ids [tile_id, tile_id+run_length). Go (directory.go DeserializeEntries), JS (index.ts deserializeIndex) and Python (tile.py deserialize_directory) all implement exactly this.
  src: https://github.com/protomaps/go-pmtiles/blob/main/pmtiles/directory.go
- [verified] find_tile(entries, tileId) in all three reference impls: binary search m=0,n=len-1; k=(m+n)>>1; cmp=tileId-entries[k].tileId; >0 -> m=k+1; <0 -> n=k-1; ==0 return entries[k]. After loop (m>n): if n>=0 and (entries[n].runLength==0 OR tileId-entries[n].tileId < entries[n].runLength) return entries[n]; else null. Lookup loop (JS getZxyAttempt / Python Reader.get): start at (rootOffset, rootLength); for depth 0..3: dir=decode(fetch(off,len)); e=findTile(dir,id); if none -> tile absent; if e.runLength>0 -> tile bytes at tileDataOffset+e.offset, length e.length, then decompress with tile_compression; else recurse with off=leafDirsOffset+e.offset, len=e.length. More than 3 levels throws.
  src: https://github.com/protomaps/PMTiles/blob/main/js/src/index.ts
- [verified] Reference Hilbert code (go-pmtiles tile_id.go): rotate(n,x,y,rx,ry): if ry==0 { if rx!=0 { x=n-1-x; y=n-1-y }; return (y,x) } else return (x,y). ZxyToID(z,x,y): acc=(1<<(2z)-1)/3 (= sum of 4^k for k<z); n=z-1; for s=1<<n; s>0; s>>=1: rx=s&x; ry=s&y; acc += ((3*rx)^ry)<<n; (x,y)=rotate(s,x,y,rx,ry); n--. IDToZxy(i): z=(bits.Len64(3i+1)-1)/2; acc as above; t=i-acc; tx=ty=0; for a in 0 until z: s=1<<a; rx=1&(t>>1); ry=1&(t^rx); (tx,ty)=rotate(s,tx,ty,rx,ry); tx+=rx<<a; ty+=ry<<a; t>>=2. ParentID(i)=parentAcc+(i-acc)/4. In Go the z=0 case works only because uint32 n underflows and 1<<0xFFFFFFFF==0 skips the loop; Kotlin must guard z==0 explicitly (Kotlin shl masks the shift count).
  src: https://github.com/protomaps/go-pmtiles/blob/main/pmtiles/tile_id.go
- [verified] Tile ID test vectors from spec + Go/JS/Python tests: (0,0,0)=0; (1,0,0)=1; (1,0,1)=2; (1,1,1)=3; (1,1,0)=4; (2,0,0)=5; (12,3423,1763)=19078479 (and inverse). Additional vectors I computed with the reference algorithm and cross-checked by round trip: z3 first (3,0,0)=21; (4,0,0)=85; (15,0,0)=357913941; (16,0,0)=1431655765; z2 full map (x,y)->id: (0,0)=5,(0,1)=8,(0,2)=9,(0,3)=10,(1,0)=6,(1,1)=7,(1,2)=12,(1,3)=11,(2,0)=19,(2,1)=18,(2,2)=13,(2,3)=14,(3,0)=20,(3,1)=17,(3,2)=16,(3,3)=15; (15,32767,0)=1431655764; (15,32767,32767)=1073741823; (15,0,32767)=715827882; Sofia (15,18506,12078)=1268026253. Round trip verified for all tiles z0..z9 (Go test) and sampled to z16 (mine). Varint vectors: bytes 00 01 7F E5 8E 26 -> 0,1,127,624485; FF FF FF FF FF FF FF 0F -> 9007199254740991.
  src: https://github.com/protomaps/go-pmtiles/blob/main/pmtiles/tile_id_test.go
- [verified] go-pmtiles Extract algorithm (extract.go): (1) fetch header bytes 0..126; require header.Clustered else error 'source archive must be clustered for extracts'; clamp minzoom/maxzoom to header. (2) Build a roaring64 'relevance bitmap' of tile ids: for a bbox/region at maxzoom, bitmapMultiPolygon() adds ids of tiles covering each ring (tilecover) plus interior tiles (by checking tile center inside polygon), then generalizeOr() adds ParentID of every id down to minzoom. With no region: AddRange(ZxyToID(minzoom,0,0), ZxyToID(maxzoom+1,0,0)). (3) fetch root dir; RelevantEntries(bitmap, maxzoom, rootDir) returns (tiles, leaves). (4) leaf byte ranges (leafDirsOffset+offset, length) are merged with mergeRanges(overfetch) and fetched; each leaf decoded and passed through RelevantEntries again; a leaf yielding further leaves panics ('This doesn't support leaf level 2+'). (5) sort tile entries by TileID; reencodeEntries dedupes by source offset and produces contiguous SrcDstRanges; (6) mergeRanges(tileParts, overfetch); (7-11) write header/root/metadata/leaves then download chunks with downloadThreads workers, writing header LAST so a cancelled extract is not mistaken for valid.
  src: https://github.com/protomaps/go-pmtiles/blob/main/pmtiles/extract.go
- [verified] RelevantEntries(bitmap, maxzoom, dir): for a leaf entry (runLength==0) the leaf covers ids [entry.tileId, nextEntry.tileId) or, for the last entry, [entry.tileId, ZxyToID(maxzoom+1,0,0)); it is relevant if the bitmap intersects that range. runLength==1: relevant if bitmap contains tileId. runLength>1: iterate y in [tileId, tileId+runLength) and emit trimmed sub-runs (same offset/length, new tileId/runLength) for each maximal consecutive run of wanted ids. Test: entry {id 0, rl 5} with bitmap {1,2,4} -> two entries with runLengths 2 and 1.
  src: https://github.com/protomaps/go-pmtiles/blob/main/pmtiles/extract_test.go
- [verified] reencodeEntries(dir sorted by tileId): seenOffsets map[srcOffset]dstOffset; for each entry, if offset already seen -> emit entry pointing at the existing dst offset (dedupe, no new range); else if last range's src end == entry.offset extend it, otherwise start a new range {SrcOffset, DstOffset, Length}; then dst += length. Returns reencoded entries, ranges, total tile bytes, addressedTiles = sum(runLength), tileContents = len(seenOffsets). Tests: [{0,400,10,1},{1,500,20,2}] -> 2 ranges, datalen 30, addressed 3, contents 2; [{0,400,10,1},{1,500,20,1},{2,400,10,1}] -> 2 ranges, third entry offset 0; [{0,400,10,0},{1,410,20,0}] -> 1 range {400,30}.
  src: https://github.com/protomaps/go-pmtiles/blob/main/pmtiles/extract.go
- [verified] mergeRanges(ranges, overfetch): for each range compute bytesToNext = next.SrcOffset - (SrcOffset+Length) (MaxInt64 if last or negative); budget = totalWantedBytes * overfetch; sort by bytesToNext ascending; while >1 items and budget - smallest gap >= 0: merge item into its successor (new length = len + gap + next.len, CopyDiscards appended so the reader copies 'Wanted' bytes then discards 'Discard' gap bytes), budget -= gap. Result sorted by Length descending. Test: [{0,0,50},{60,60,60}] overfetch 0.1 -> one request {0,0,120} with CopyDiscards [{50,10},{60,0}], total 120; [{0,0,50},{60,60,10},{80,80,10}] overfetch 0.3 -> one request of 90 bytes. Because merging one gap never changes other gaps, this greedy is equivalent to 'sort gaps ascending, close gaps while budget remains'.
  src: https://github.com/protomaps/go-pmtiles/blob/main/pmtiles/extract_test.go
- [verified] go-pmtiles CLI defaults (main.go): extract --minzoom -1 (=header min), --maxzoom -1 (=header max), --download-threads 4, --overfetch 0.05 ('What ratio of extra data to download to minimize # requests; 0.2 is 20%'), --dry-run available, --bbox 'min_lon,min_lat,max_lon,max_lat', --region GeoJSON file. Docs: 'Extracting a full sub-pyramid from 0 to maxzoom is always an efficient operation'; --minzoom>0 'may require many more requests'.
  src: https://github.com/protomaps/go-pmtiles/blob/main/main.go
- [verified] Live header of https://build.protomaps.com/20260906.pmtiles (decoded 2026-09-07): magic OK, version 3, root_dir_offset 127, root_dir_length 15568, metadata_offset 137472353823, metadata_length 1178, leaf_dirs_offset 137472355001, leaf_dirs_length 351633465 (~351 MB), tile_data_offset 16384, tile_data_length 137472337439, addressed_tiles 1431655765 (= every tile z0..z15 = (4^16-1)/3), tile_entries 177556079, tile_contents 135656626, clustered 1, internal_compression 2 (gzip), tile_compression 2 (gzip), tile_type 1 (MVT), minzoom 0, maxzoom 15, bounds e7 -1800000000,-850511287,1800000000,850511287, center_zoom 0, center 0,0. Total file 137823988466 bytes (~137.8 GB).
  src: https://build.protomaps.com/20260906.pmtiles
- [verified] Live root directory of the planet build: gzip (1f8b), decompresses 15568 -> 23460 bytes, 2917 entries, ALL of them leaf pointers (run_length 0, zero tile entries in root); leaf offsets are contiguous and end exactly at leaf_dirs_length; leaf sizes 1389..163160 bytes, average ~120 KB; first leaf id 0, last leaf id 1428073152. A sampled leaf (covering Sofia, z15) had 60876 entries (320 KB decompressed), no nested leaves, 538 run_length>1 entries (max run 20), 1701 entries sharing an offset with an earlier entry (dedupe), 1729 backward offset references. The tile blob fetched via root->leaf->tile was 114887 bytes starting 1f 8b (gzip) and decompressed to a protobuf starting 0x1a (MVT layer field).
  src: https://build.protomaps.com/20260906.pmtiles
- [verified] Live planet metadata JSON (1178 bytes gzip, decompresses to JSON object) keys: vector_layers (9 layers: boundaries, buildings, earth, landcover, landuse, places, pois, roads, water; each {id, fields{name:'String'|'Number'|'Boolean'}, minzoom, maxzoom}), attribution ('<a href="https://www.openstreetmap.org/copyright" target="_blank">&copy; OpenStreetMap</a>'), description ('Basemap layers derived from OpenStreetMap and Natural Earth'), name ('Protomaps Basemap'), type ('baselayer'), version ('4.15.2'), plus pgf:devanagari:name, pgf:devanagari:version, planetiler:buildtime, planetiler:githash, planetiler:osm:osmosisreplicationseq/time/url, planetiler:version. There is NO tilestats, minzoom, maxzoom, bounds, center or format key in the planet metadata (those live in the binary header).
  src: https://build.protomaps.com/20260906.pmtiles
- [verified] Python pmtiles_to_mbtiles (PMTiles/python/pmtiles/pmtiles/convert.py) creates 'CREATE TABLE metadata (name text, value text)' and 'CREATE TABLE tiles (zoom_level integer, tile_column integer, tile_row integer, tile_data blob)'; fills metadata from the PMTiles JSON, adding if missing: minzoom, maxzoom (from header), bounds 'minLon,minLat,maxLon,maxLat', center 'lon,lat,zoom' (header e7/1e7), format 'pbf' if tile_type MVT. Every other metadata key is written as its own row (non-string values json.dumps'd); vector_layers and tilestats are moved into a single 'json' row: json.dumps({'vector_layers':..., 'tilestats':...}). Tiles inserted as (z, x, (1<<z)-1-y, raw bytes as stored, i.e. still gzip). Finally 'CREATE UNIQUE INDEX tile_index on tiles (zoom_level, tile_column, tile_row)'. The reverse (mbtiles_to_pmtiles) force-gzips pbf tiles lacking the 1f8b magic.
  src: https://github.com/protomaps/PMTiles/blob/main/python/pmtiles/pmtiles/convert.py
- [verified] MBTiles 1.3 spec: metadata MUST have name and format; 'pbf' as format refers to gzip-compressed Mapbox Vector Tile data; SHOULD have bounds (left,bottom,right,top), center (lon,lat,zoom), minzoom, maxzoom; MAY have attribution, description, type, version; if format is pbf MUST have 'json' row whose object contains vector_layers. tiles table uses TMS row numbering: tile_row = 2^z - 1 - y (spec example: XYZ 11/327/791 stored as tile_row 1256).
  src: https://github.com/mapbox/mbtiles-spec/blob/master/1.3/spec.md
- [verified] MapLibre Native mbtiles:// reader (platform/default/src/mln/storage/mbtiles_file_source.cpp, main): request_tile queries 'SELECT tile_data FROM tiles where zoom_level = z AND tile_column = x AND tile_row = (pow(2,z)-1) - y', and if util::is_compressed(data) (checks gzip magic 1f 8b, or zlib 0x78) it decompresses before handing the tile to the parser, so storing gzip MVT blobs as-is is correct. request_tilejson reads all metadata rows (the 'json' row is parsed and merged), sets scheme 'xyz', defaults format to 'png' if missing, and derives zoom range with 'SELECT MIN(zoom_level),MAX(zoom_level) from tiles'. URL scheme constant is mln::util::MBTILES_PROTOCOL.
  src: https://github.com/maplibre/maplibre-native/blob/main/platform/default/src/mln/storage/mbtiles_file_source.cpp
- [verified] build.protomaps.com HTTP behaviour (probed 2026-09-07): HEAD returns 200 with content-type application/octet-stream, content-length, strong ETag (e.g. "fab2d5723fc67aa2540872bd57c80b0d-514"), last-modified, accept-ranges: bytes, server: cloudflare. GET with 'Range: bytes=0-16383' returns HTTP 206 with content-length 16384 and 'content-range: bytes 0-16383/137823988466'; a mid-file range (bytes 137472353823-137472355000) also returned 206 with the exact requested bytes. Files exist for 20260907 (137838277849 B), 20260906, 20260905; 20260831 is already 404 (retention: docs say builds are kept for the past week plus latest per patch version).
  src: https://build.protomaps.com/20260907.pmtiles
- [verified] Protomaps docs (basemaps/downloads): planet ~120 GB (now ~138 GB) with zooms 0-15, licensed ODbL Produced Work requiring OpenStreetMap attribution, builds listed at maps.protomaps.com/builds, compatible with @protomaps/basemaps style v4+. Explicitly: 'URLs may change' and 'hotlinking to these downloads are discouraged. Instead, you should copy the tileset to your own Cloud Storage.' Use 'pmtiles extract --maxzoom' for partial downloads.
  src: https://docs.protomaps.com/basemaps/downloads
- [verified] JS FetchSource details worth copying: Range header is exactly `bytes=${offset}-${offset+length-1}`; it treats a 416 on offset 0 as 'archive smaller than 16384' and re-requests bytes=0-(size-1) using Content-Range 'bytes */N'; weak ETags (W/) are ignored; an ETag change between requests raises EtagMismatch and forces a re-read of the header; a 200 response without Content-Length or with Content-Length > requested length means the server ignored Range and is treated as an error.
  src: https://github.com/protomaps/PMTiles/blob/main/js/src/index.ts
- [verified] Web Mercator tile math (standard, cross-checked against the MBTiles spec example: lon -122.4, lat 37.8 -> z11 tile (327,791)): x = floor((lon+180)/360 * 2^z); y = floor((1 - ln(tan(latR) + 1/cos(latR))/pi)/2 * 2^z), clamp to [0, 2^z-1], lat clamped to +/-85.05112878. For a bbox, x range from minLon..maxLon and y range from maxLat (north, smaller y)..minLat. go-pmtiles uses a polygon tilecover + interior fill + parent generalisation instead; for an axis-aligned bbox the per-zoom x/y range enumeration yields the same set (bbox covering at every zoom minzoom..maxzoom).
  src: https://github.com/protomaps/go-pmtiles/blob/main/pmtiles/bitmap.go
- [verified] Dry run of the full extract algorithm (root -> 8 leaves -> RelevantEntries -> dedupe -> mergeRanges) for a 10 km-radius bbox around Sofia (23.32E, 42.70N), z0..15, against the live 20260906 build: 780 tile ids, 780 entries (no dedupe hits inside the bbox), 8 leaf directories touched (~1.02 MB of leaf bytes), 14,053,093 tile bytes in 70 raw contiguous ranges. mergeRanges: overfetch 0 -> 70 requests; 0.05 -> 36 requests / 14.72 MB (+4.8%); 0.2 -> 21 requests / 16.5 MB; 1.0 -> 15 requests / 27.3 MB. Per-zoom tile counts for that 10 km box: z0-8: 1 each, z9-10: 2, z11: 6, z12: 16, z13: 49, z14: 144, z15: 552. Tile counts (not bytes) for other radii: 100 km z<=12: 1173 tiles / 5 leaves; 100 km z<=15: 67,030 tiles / 13 leaves; 1000 km z<=10: 6,956 tiles / 3 leaves; 1000 km z<=12: 105,793 tiles / 9 leaves; 10000 km z<=7: 9,893 tiles / 1 leaf; z<=8: 39,118 tiles.
  src: https://build.protomaps.com/20260906.pmtiles
- [verified] JS zxyToTileId/tileIdToZxy refuse z > 26 because of double precision (2^53); Python allows z <= 31 (64-bit). Kotlin with Long is safe to z31; tile ids for z15 (< 1.5e9) fit in Int but z16+ ids exceed Int, so use Long everywhere for ids and offsets. Entry length and run_length are uint32 in Go (fit in Kotlin Int as long as < 2^31, which holds for real archives).
  src: https://github.com/protomaps/PMTiles/blob/main/python/pmtiles/pmtiles/tile.py
- [likely] Approximate byte sizes for radii other than 10 km were NOT measured; Sofia-area z15 tiles averaged ~18 KB gzip in the 10 km sample (14 MB / 780) but z15 tiles are as large as 115 KB in dense city centres and near-empty over sea/rural land, so a 100 km z15 extract could plausibly be 0.5-2 GB. Measure with the dry-run path (directories only) before downloading and show the user the exact byte total.
  src: https://build.protomaps.com/20260906.pmtiles

## Gotchas
- Kotlin shift semantics: `1 shl -1` is NOT 0 (shift count is masked to 5/6 bits), so a literal port of Go's ZxyToID loop breaks for z=0. Return acc (=0) immediately when z==0, and use Long (`1L shl n`) for acc, s, rx, ry so z>=16 ids and 2z<=62 shifts do not overflow Int.
- Two different offset bases: leaf-directory entry offsets are relative to header.leaf_dirs_offset (planet: 137472355001), tile entry offsets are relative to header.tile_data_offset (planet: 16384). Mixing them up yields garbage that still often starts with a gzip header from a neighbouring tile.
- RelevantEntries must bound the LAST leaf entry's range with zxyToTileId(maxzoom+1,0,0), where maxzoom is the header's (15 -> 1431655765), not your requested maxzoom; otherwise the last leaf may be wrongly skipped or included.
- Each daily file has different byte offsets. Pin ONE URL (date) for the whole extract, and compare the ETag of every 206 response with the ETag captured on the header request; abort/restart on mismatch (the JS reader does exactly this). Do not mix leaves/tiles from two dates into one download session.
- Builds are only kept about a week (20260831 already 404 on 2026-09-07). Discover the newest date by HEAD-probing today, then yesterday, etc.; never hard-code a date. Yesterday's file may be the safest default since today's may still be uploading (both 20260906 and 20260907 existed at 18:18 UTC).
- Protomaps docs explicitly discourage hotlinking build.protomaps.com ('URLs may change'). For a shipped app consider mirroring a build to your own storage (R2/S3 with Range support) or at least make the base URL remotely configurable; Range support (206, Accept-Ranges, strong ETag) is what your downloader must require of any mirror.
- Always check resp.code == 206 and that the body length equals the requested length. A 200 means the server ignored Range and is about to stream 138 GB to the phone; abort the call immediately (OkHttp: cancel the call / close the body without reading).
- Dedupe/backward references: within a leaf, ~3% of entries pointed at an earlier offset (identical tiles, e.g. empty ocean). When you dedupe by source offset, a deduped tile's FIRST occurrence may be outside your bbox, so source offsets are not monotonic in tile-id order; sort blobs by source offset before coalescing, or (like Go) treat negative gaps as non-mergeable.
- Run-length entries: one entry with run_length N covers N consecutive tile ids that share ONE blob. After trimming to the wanted set, you must still write N separate rows into MBTiles (tileIdToZxy for each id), all with the same blob.
- MBTiles rows use TMS: tile_row = (1 shl z) - 1 - y. PMTiles Hilbert ids use XYZ y (north = 0). Never flip before computing tile ids; only flip when inserting into SQLite. MapLibre Native flips back with pow(2,z)-1-y on read.
- Keep tile blobs gzip-compressed (planet tile_compression = 2). MBTiles 'format=pbf' is defined as gzip MVT and MapLibre Native's mbtiles reader auto-detects 1f8b / zlib and decompresses. If a future build used brotli or zstd (3/4) you would have to decompress and re-gzip, because MapLibre only detects gzip/zlib; if tile_compression were 1 (none) you should gzip before storing to match the pbf convention.
- Root directory can contain a MIX of tile entries and leaf entries in general (spec case 2 TODO in Go), even though the current planet root is 100% leaf pointers. Handle both in the same loop (run_length>0 -> tile, ==0 -> leaf).
- Metadata: the planet metadata has no minzoom/maxzoom/bounds/center/format keys; derive minzoom/maxzoom/bounds/center for the MBTiles 'metadata' table from your OWN extract (your bbox, chosen zoom range) not from the planet header, and put vector_layers inside the 'json' row (MapLibre parses that row). Overwrite bounds/minzoom/maxzoom on every merge so they describe the union.
- SQLite performance on Android: wrap inserts in transactions of a few thousand rows, use a prepared 'INSERT OR REPLACE INTO tiles VALUES (?,?,?,?)' with the UNIQUE index on (zoom_level, tile_column, tile_row) present, and consider PRAGMA journal_mode=WAL / synchronous=NORMAL during download. MapLibre may hold the DB open read-only; write to a temp file per download and merge, or close/reopen the map source after the download.
- Memory: a planet leaf decodes to ~60k entries (320 KB raw); keep at most the relevant leaves in memory and free them after collecting entries. The wanted-id set for 100 km at z15 is ~67k Longs (fine); 1000 km at z15 would be millions (do not offer that combination). Enumerate ids per zoom into a sorted LongArray/TreeSet rather than materialising a roaring bitmap.
- Overfetch merges can produce very large single requests (5.7-7.9 MB in the 10 km sample, potentially hundreds of MB for large areas). Cap chunk size (e.g. 8-16 MB) so progress/resume works on flaky mobile networks, and stream each chunk straight into SQLite instead of buffering the whole chunk.
- Header fetch: request bytes 0-16383 in one go (spec guarantees root fits) and slice the root out of that buffer using root_dir_offset/length from the header; do not assume offset 127 or length 16257.
- Antimeridian: if minLon > maxLon (bbox crosses 180), go-pmtiles splits into two polygons; for a radius-around-point UI you can simply clamp or split the x range into [x0..2^z-1] and [0..x1].
- varint decoding must guard against >10 bytes / shift > 63 and buffer overrun (JS throws 'Expected varint not more than 10 bytes'); Python raises EOFError on truncated input. Truncated 206 bodies would otherwise silently decode to nonsense.

## Snippets
### Header parsing (127 bytes, little-endian) exactly per spec / go-pmtiles DeserializeHeader
```
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class PmHeader(
    val rootOffset: Long, val rootLength: Long,
    val metadataOffset: Long, val metadataLength: Long,
    val leafDirsOffset: Long, val leafDirsLength: Long,
    val tileDataOffset: Long, val tileDataLength: Long,
    val addressedTiles: Long, val tileEntries: Long, val tileContents: Long,
    val clustered: Boolean,
    val internalCompression: Int, // 0 unknown,1 none,2 gzip,3 brotli,4 zstd
    val tileCompression: Int,
    val tileType: Int,            // 0 unknown,1 MVT,2 PNG,3 JPEG,4 WebP,5 AVIF,6 MLT
    val minZoom: Int, val maxZoom: Int,
    val minLonE7: Int, val minLatE7: Int, val maxLonE7: Int, val maxLatE7: Int,
    val centerZoom: Int, val centerLonE7: Int, val centerLatE7: Int,
)

const val PM_HEADER_LEN = 127

fun parseHeader(bytes: ByteArray): PmHeader {
    require(bytes.size >= PM_HEADER_LEN) { "short header" }
    require(String(bytes, 0, 7, Charsets.US_ASCII) == "PMTiles") { "bad magic" }
    require(bytes[7].toInt() == 3) { "unsupported spec version ${bytes[7]}" }
    val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    fun u8(i: Int) = bytes[i].toInt() and 0xFF
    return PmHeader(
        rootOffset = b.getLong(8), rootLength = b.getLong(16),
        metadataOffset = b.getLong(24), metadataLength = b.getLong(32),
        leafDirsOffset = b.getLong(40), leafDirsLength = b.getLong(48),
        tileDataOffset = b.getLong(56), tileDataLength = b.getLong(64),
        addressedTiles = b.getLong(72), tileEntries = b.getLong(80), tileContents = b.getLong(88),
        clustered = u8(96) == 1,
        internalCompression = u8(97), tileCompression = u8(98), tileType = u8(99),
        minZoom = u8(100), maxZoom = u8(101),
        minLonE7 = b.getInt(102), minLatE7 = b.getInt(106),
        maxLonE7 = b.getInt(110), maxLatE7 = b.getInt(114),
        centerZoom = u8(118), centerLonE7 = b.getInt(119), centerLatE7 = b.getInt(123),
    )
}
// Expected for build.protomaps.com/20260906.pmtiles: rootOffset=127 rootLength=15568 tileDataOffset=16384
// leafDirsOffset=137472355001 leafDirsLength=351633465 metadataOffset=137472353823 metadataLength=1178
// clustered=true internalCompression=2 tileCompression=2 tileType=1 minZoom=0 maxZoom=15
```
### Unsigned LEB128 varint reader and directory decoding (spec A.2 / go DeserializeEntries)
```
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.util.zip.GZIPInputStream

class Cursor(val buf: ByteArray, var pos: Int = 0)

fun readVarint(c: Cursor): Long {
    var result = 0L
    var shift = 0
    while (true) {
        if (c.pos >= c.buf.size) throw EOFException("truncated varint")
        val b = c.buf[c.pos++].toInt() and 0xFF
        result = result or ((b and 0x7F).toLong() shl shift)
        if (b and 0x80 == 0) return result
        shift += 7
        if (shift > 63) throw IOException("varint longer than 10 bytes")
    }
}
// Test vector: bytes 00 01 7F E5 8E 26 -> 0, 1, 127, 624485

fun gunzip(data: ByteArray): ByteArray =
    GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }

data class Entry(val tileId: Long, val offset: Long, val length: Int, val runLength: Int) {
    val isLeaf get() = runLength == 0
}

fun decodeDirectory(raw: ByteArray, internalCompression: Int): List<Entry> {
    val data = when (internalCompression) {
        1 -> raw
        2 -> gunzip(raw)
        else -> throw IOException("unsupported internal compression $internalCompression")
    }
    val c = Cursor(data)
    val n = readVarint(c).toInt()
    require(n > 0) { "empty directory is invalid" }
    val ids = LongArray(n); var last = 0L
    for (i in 0 until n) { last += readVarint(c); ids[i] = last }      // delta-encoded
    val runLengths = IntArray(n) { readVarint(c).toInt() }
    val lengths = IntArray(n) { readVarint(c).toInt() }
    val offsets = LongArray(n)
    for (i in 0 until n) {
        val v = readVarint(c)
        offsets[i] = if (v == 0L && i > 0) offsets[i - 1] + lengths[i - 1] else v - 1
    }
    return List(n) { Entry(ids[it], offsets[it], lengths[it], runLengths[it]) }
}
// Roundtrip test (from go/python tests): entries [(0,0,0,0),(1,1,1,1),(2,2,2,2)] serialize+decode -> identical.
// Live check: planet root -> 2917 entries, all runLength==0, offsets contiguous, last offset+length == leafDirsLength.
```
### findTile (binary search + run-length + leaf fallthrough) and single-tile lookup with leaf recursion
```
fun findTile(entries: List<Entry>, tileId: Long): Entry? {
    var m = 0
    var n = entries.size - 1
    while (m <= n) {
        val k = (m + n) ushr 1
        val cmp = tileId.compareTo(entries[k].tileId)
        when {
            cmp > 0 -> m = k + 1
            cmp < 0 -> n = k - 1
            else -> return entries[k]
        }
    }
    // m > n here
    if (n >= 0) {
        val e = entries[n]
        if (e.runLength == 0) return e                       // leaf directory covering this id
        if (tileId - e.tileId < e.runLength) return e        // inside a run
    }
    return null
}
// Tests (JS/Go/Python): [] ,101 -> null; [{100,1,1,1}],100 -> off 1; ,101 -> null;
// [{3,3,1,2},{5,5,1,2}],4 -> off 3; [{100,1,1,2}],101 -> off 1; [{100,1,1,1},{150,2,2,2}],151 -> off 2 len 2;
// [{50,1,1,2},{100,2,2,1},{150,3,3,1}],51 -> off 1; leaf [{100,1,1,0}],150 -> off 1 (leaf).

/** fetch(offset, length) returns exactly `length` bytes from the archive (HTTP Range). */
suspend fun getTileBytes(h: PmHeader, z: Int, x: Int, y: Int,
                         fetch: suspend (Long, Long) -> ByteArray): ByteArray? {
    if (z < h.minZoom || z > h.maxZoom) return null
    val tileId = zxyToTileId(z, x, y)
    var dirOff = h.rootOffset
    var dirLen = h.rootLength
    repeat(4) {                                       // reference impls allow depth 0..3
        val dir = decodeDirectory(fetch(dirOff, dirLen), h.internalCompression)
        val e = findTile(dir, tileId) ?: return null
        if (e.runLength > 0) {
            return fetch(h.tileDataOffset + e.offset, e.length.toLong())   // still tile_compression (gzip)
        }
        dirOff = h.leafDirsOffset + e.offset            // leaf offsets are relative to leafDirsOffset
        dirLen = e.length.toLong()
    }
    throw IOException("maximum directory depth exceeded")
}
```
### Hilbert tile id math (port of go-pmtiles tile_id.go) with z==0 guard for Kotlin shift semantics
```
private fun rotate(n: Long, x: Long, y: Long, rx: Long, ry: Long): LongArray {
    if (ry == 0L) {
        return if (rx != 0L) longArrayOf(n - 1 - y, n - 1 - x) else longArrayOf(y, x)
    }
    return longArrayOf(x, y)
}

fun zxyToTileId(z: Int, x: Int, y: Int): Long {
    require(z in 0..31) { "zoom out of range" }
    val dim = 1L shl z
    require(x >= 0 && y >= 0 && x < dim && y < dim) { "tile x/y outside zoom level bounds" }
    var acc = ((1L shl (2 * z)) - 1) / 3          // number of tiles in zooms 0..z-1
    if (z == 0) return acc                          // Go relies on uint32 underflow here; Kotlin must guard
    var tx = x.toLong(); var ty = y.toLong()
    var a = z - 1
    var s = 1L shl a
    while (s > 0) {
        val rx = tx and s
        val ry = ty and s
        acc += ((3 * rx) xor ry) shl a
        val r = rotate(s, tx, ty, rx, ry); tx = r[0]; ty = r[1]
        a--
        s = s ushr 1
    }
    return acc
}

fun tileIdToZxy(id: Long): Triple<Int, Int, Int> {
    require(id >= 0)
    val z = (63 - java.lang.Long.numberOfLeadingZeros(3 * id + 1)) / 2   // == (bits.Len64(3i+1)-1)/2
    require(z <= 31) { "tile id too large" }
    val acc = ((1L shl (2 * z)) - 1) / 3
    var t = id - acc
    var tx = 0L; var ty = 0L
    for (a in 0 until z) {
        val s = 1L shl a
        val rx = 1L and (t ushr 1)
        val ry = 1L and (t xor rx)
        val r = rotate(s, tx, ty, rx, ry); tx = r[0]; ty = r[1]
        tx += rx shl a
        ty += ry shl a
        t = t ushr 2
    }
    return Triple(z, tx.toInt(), ty.toInt())
}

fun parentTileId(id: Long): Long {
    val z = (63 - java.lang.Long.numberOfLeadingZeros(3 * id + 1)) / 2
    val acc = ((1L shl (2 * z)) - 1) / 3
    val parentAcc = ((1L shl (2 * (z - 1))) - 1) / 3
    return parentAcc + (id - acc) / 4
}

/* Unit-test vectors:
 zxyToTileId(0,0,0)=0; (1,0,0)=1; (1,0,1)=2; (1,1,1)=3; (1,1,0)=4; (2,0,0)=5; (3,0,0)=21; (4,0,0)=85;
 (12,3423,1763)=19078479; (15,0,0)=357913941; (16,0,0)=1431655765; (15,32767,0)=1431655764;
 (15,32767,32767)=1073741823; (15,0,32767)=715827882; (15,18506,12078)=1268026253 (Sofia).
 z2: (0,0)=5 (0,1)=8 (0,2)=9 (0,3)=10 (1,0)=6 (1,1)=7 (1,2)=12 (1,3)=11 (2,0)=19 (2,1)=18 (2,2)=13 (2,3)=14 (3,0)=20 (3,1)=17 (3,2)=16 (3,3)=15
 tileIdToZxy(19078479)=(12,3423,1763); round trip for all tiles z0..z9 and the 4 corners of every z0..z31.
 parentTileId(zxyToTileId(19,1000,3)) == zxyToTileId(18,500,1). */
```
### Bbox around a point -> per-zoom Web Mercator tile ranges -> sorted set of wanted tile ids (equivalent of go-pmtiles' relevance bitmap for an axis-aligned bbox)
```
import kotlin.math.*
import java.util.TreeSet

const val MAX_MERC_LAT = 85.05112878

data class Bbox(val minLon: Double, val minLat: Double, val maxLon: Double, val maxLat: Double)

fun bboxAround(lat: Double, lon: Double, radiusKm: Double): Bbox {
    val dLat = radiusKm / 111.32
    val dLon = radiusKm / (111.32 * cos(Math.toRadians(lat)).coerceAtLeast(1e-6))
    return Bbox(
        (lon - dLon).coerceIn(-180.0, 180.0), (lat - dLat).coerceIn(-MAX_MERC_LAT, MAX_MERC_LAT),
        (lon + dLon).coerceIn(-180.0, 180.0), (lat + dLat).coerceIn(-MAX_MERC_LAT, MAX_MERC_LAT),
    )
}

fun lonToTileX(lon: Double, z: Int): Int {
    val n = 1 shl z
    return floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
}

fun latToTileY(lat: Double, z: Int): Int {
    val n = 1 shl z
    val latR = Math.toRadians(lat.coerceIn(-MAX_MERC_LAT, MAX_MERC_LAT))
    return floor((1.0 - ln(tan(latR) + 1.0 / cos(latR)) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
}
// check: lonToTileX(-122.4, 11) == 327, latToTileY(37.8, 11) == 791 (MBTiles spec example tile 11/327/791)

/** Every tile in zooms minZ..maxZ whose extent intersects bbox (full sub-pyramid, like `pmtiles extract --bbox`). */
fun wantedTileIds(bbox: Bbox, minZ: Int, maxZ: Int): TreeSet<Long> {
    val out = TreeSet<Long>()
    for (z in minZ..maxZ) {
        val x0 = lonToTileX(bbox.minLon, z); val x1 = lonToTileX(bbox.maxLon, z)
        val y0 = latToTileY(bbox.maxLat, z); val y1 = latToTileY(bbox.minLat, z)   // north edge = smaller y
        for (x in x0..x1) for (y in y0..y1) out.add(zxyToTileId(z, x, y))
    }
    return out
}
// 10 km around Sofia, z0..15 -> 780 ids; per zoom: 1,1,1,1,1,1,1,1,1,2,2,6,16,49,144,552

/** Same semantics as go-pmtiles RelevantEntries: split a directory into wanted tile entries (runs trimmed) and leaves to descend into. */
fun relevantEntries(wanted: TreeSet<Long>, headerMaxZoom: Int, dir: List<Entry>): Pair<List<Entry>, List<Entry>> {
    val lastTile = zxyToTileId(headerMaxZoom + 1, 0, 0)     // bound for the final leaf's id range
    val tiles = ArrayList<Entry>(); val leaves = ArrayList<Entry>()
    for ((idx, e) in dir.withIndex()) {
        when {
            e.runLength == 0 -> {
                val end = if (idx == dir.size - 1) lastTile else dir[idx + 1].tileId
                if (!wanted.subSet(e.tileId, true, end, false).isEmpty()) leaves += e   // ids in [tileId, end)
            }
            e.runLength == 1 -> if (wanted.contains(e.tileId)) tiles += e
            else -> {
                var runStart = 0L; var run = 0
                for (id in e.tileId until e.tileId + e.runLength) {
                    if (wanted.contains(id)) { if (run == 0) runStart = id; run++ }
                    else if (run > 0) { tiles += Entry(runStart, e.offset, e.length, run); run = 0 }
                }
                if (run > 0) tiles += Entry(runStart, e.offset, e.length, run)
            }
        }
    }
    return tiles to leaves
}
// Test (extract_test.go): dir=[{0,0,0,5}], wanted={1,2,4} -> tiles [{1,..,rl 2},{4,..,rl 1}], no leaves.
// dir=[{0,..,rl0},{2,..,rl1},{4,..,rl0}], wanted={3} -> no tiles, no leaves (3 is in [2,4) but entry 2 has rl 1).
```
### Range coalescing with overfetch budget (equivalent greedy to go-pmtiles mergeRanges, plus a chunk-size cap for mobile) and blob->tile mapping
```
/** A unique blob in the source tile-data section (after dedupe by offset). */
data class Blob(val srcOffset: Long, val length: Int, val entries: MutableList<Entry> = ArrayList())

/** One HTTP range request: [start, end) relative to tile_data_offset, with the blobs it contains (sorted). */
data class Chunk(val start: Long, var end: Long, val blobs: MutableList<Blob>) { val length get() = end - start }

fun dedupeBlobs(tileEntries: List<Entry>): List<Blob> {
    val byOffset = HashMap<Long, Blob>()
    for (e in tileEntries) {
        byOffset.getOrPut(e.offset) { Blob(e.offset, e.length) }.entries += e   // identical tiles share one blob
    }
    return byOffset.values.sortedBy { it.srcOffset }        // sort by SOURCE offset (dedupe makes tile order non-monotonic)
}

/**
 * overfetch = 0.05 means we may download up to 5% extra bytes to reduce the number of requests
 * (go-pmtiles default). Because closing one gap never changes another gap, 'close the smallest
 * gaps first while the budget lasts' is exactly the reference greedy. maxChunk keeps single
 * requests resumable on mobile (not in go-pmtiles).
 */
fun coalesce(blobs: List<Blob>, overfetch: Double, maxChunk: Long = 16L shl 20): List<Chunk> {
    if (blobs.isEmpty()) return emptyList()
    // 1. strictly adjacent blobs -> one chunk (free)
    val chunks = ArrayList<Chunk>()
    for (b in blobs) {
        val last = chunks.lastOrNull()
        if (last != null && last.end == b.srcOffset && last.length + b.length <= maxChunk) {
            last.end += b.length; last.blobs += b
        } else chunks += Chunk(b.srcOffset, b.srcOffset + b.length, mutableListOf(b))
    }
    // 2. spend the overfetch budget on the smallest gaps
    val wanted = blobs.sumOf { it.length.toLong() }
    var budget = (wanted * overfetch).toLong()
    val gaps = (0 until chunks.size - 1).map { i -> i to (chunks[i + 1].start - chunks[i].end) }
        .sortedBy { it.second }
    val close = BooleanArray(chunks.size)
    for ((i, gap) in gaps) {
        if (gap > budget) break
        close[i] = true; budget -= gap
    }
    val merged = ArrayList<Chunk>()
    var cur = chunks[0]
    for (i in 1 until chunks.size) {
        val next = chunks[i]
        if (close[i - 1] && (next.end - cur.start) <= maxChunk) { cur.end = next.end; cur.blobs += next.blobs }
        else { merged += cur; cur = next }
    }
    merged += cur
    return merged
}
// Test (extract_test.go semantics): blobs at [0,50) and [60,120), overfetch 0.1 (budget 11 >= gap 10) -> ONE chunk [0,120).
// [0,50),[60,70),[80,90) with 0.3 (budget 21, gaps 10+10) -> ONE chunk [0,90).
// Live 10 km Sofia: 780 blobs -> 70 chunks at 0.0, 36 at 0.05 (+4.8% bytes), 21 at 0.2, 15 at 1.0.

/** Streaming a chunk into SQLite without buffering the whole chunk. */
fun consumeChunk(chunk: Chunk, body: java.io.InputStream, sink: (z: Int, x: Int, y: Int, gz: ByteArray) -> Unit) {
    var pos = chunk.start
    val din = java.io.DataInputStream(body)
    for (b in chunk.blobs) {
        din.skipNBytes(b.srcOffset - pos)                   // discard overfetched gap bytes
        val data = ByteArray(b.length); din.readFully(data)
        pos = b.srcOffset + b.length
        for (e in b.entries) for (id in e.tileId until e.tileId + e.runLength) {
            val (z, x, y) = tileIdToZxy(id)
            sink(z, x, y, data)                              // same blob for every id in the run
        }
    }
}
```
### HTTP Range fetch on Android (OkHttp) mirroring the reference FetchSource checks, plus the full extract driver
```
import okhttp3.OkHttpClient
import okhttp3.Request

class ArchiveChangedException : IOException("archive ETag changed mid-download")

class PmSource(private val client: OkHttpClient, val url: String) {
    var etag: String? = null   // captured from the first (header) response

    /** Returns the response for bytes [offset, offset+length). Caller must close. */
    fun open(offset: Long, length: Long): okhttp3.Response {
        val req = Request.Builder().url(url)
            .header("Range", "bytes=$offset-${offset + length - 1}")   // inclusive end, as in pmtiles FetchSource
            .build()
        val resp = client.newCall(req).execute()
        if (resp.code != 206) {                     // 200 == server ignored Range: never read that body
            resp.close(); throw IOException("expected 206 Partial Content, got ${resp.code}")
        }
        val cl = resp.header("Content-Length")?.toLongOrNull()
        if (cl != null && cl != length) { resp.close(); throw IOException("bad content-length $cl != $length") }
        val newEtag = resp.header("ETag")?.takeUnless { it.startsWith("W/") }
        if (etag == null) etag = newEtag
        else if (newEtag != null && newEtag != etag) { resp.close(); throw ArchiveChangedException() }
        return resp
    }

    fun bytes(offset: Long, length: Long): ByteArray = open(offset, length).use { r ->
        val b = r.body!!.bytes(); if (b.size.toLong() != length) throw IOException("short read"); b
    }
}

/** Full 'pmtiles extract' into an MBTiles sink. */
fun extractToMbtiles(src: PmSource, bbox: Bbox, maxZoomWanted: Int, overfetch: Double, db: MbtilesWriter,
                     onProgress: (done: Long, total: Long) -> Unit) {
    val head16k = src.bytes(0, 16384)                          // header + root in one request (spec guarantee)
    val h = parseHeader(head16k)
    check(h.clustered) { "source archive must be clustered" }
    check(h.tileType == 1) { "expected MVT tiles" }
    val minZ = h.minZoom
    val maxZ = minOf(maxZoomWanted, h.maxZoom)

    val wanted = wantedTileIds(bbox, minZ, maxZ)
    val rootRaw = head16k.copyOfRange(h.rootOffset.toInt(), (h.rootOffset + h.rootLength).toInt())
    val root = decodeDirectory(rootRaw, h.internalCompression)
    val (rootTiles, leaves) = relevantEntries(wanted, h.maxZoom, root)
    val tileEntries = ArrayList(rootTiles)

    // leaves: coalesce their byte ranges too (they are contiguous in the planet, so this is usually 1-3 requests)
    val leafBlobs = leaves.map { Blob(it.srcOffset(h), it.length) }
    for (chunk in coalesce(leafBlobs, overfetch)) {
        src.open(chunk.start, chunk.length).use { r ->
            val din = java.io.DataInputStream(r.body!!.byteStream()); var pos = chunk.start
            for (b in chunk.blobs) {
                din.skipNBytes(b.srcOffset - pos); val raw = ByteArray(b.length); din.readFully(raw); pos = b.srcOffset + b.length
                val (t, l2) = relevantEntries(wanted, h.maxZoom, decodeDirectory(raw, h.internalCompression))
                check(l2.isEmpty()) { "nested leaf directories (level 2+) not supported" }
                tileEntries += t
            }
        }
    }
    tileEntries.sortBy { it.tileId }

    val blobs = dedupeBlobs(tileEntries)
    val chunks = coalesce(blobs, overfetch)
    val total = chunks.sumOf { it.length }; var done = 0L
    // metadata (tiny) -> MBTiles metadata rows
    val metaJson = String(gunzipIf(src.bytes(h.metadataOffset, h.metadataLength), h.internalCompression), Charsets.UTF_8)
    db.writeMetadata(metaJson, bbox, minZ, maxZ)

    for (chunk in chunks) {                                   // run 2-4 of these concurrently if desired
        src.open(h.tileDataOffset + chunk.start, chunk.length).use { r ->
            db.transaction {
                consumeChunk(chunk, r.body!!.byteStream()) { z, x, y, gz -> db.putTile(z, x, y, gz) }
            }
        }
        done += chunk.length; onProgress(done, total)
    }
}

private fun Entry.srcOffset(h: PmHeader) = h.leafDirsOffset + offset
private fun gunzipIf(b: ByteArray, compression: Int) = if (compression == 2) gunzip(b) else b
```
### MBTiles writer: schema, TMS y-flip, metadata rows matching python pmtiles_to_mbtiles + MBTiles 1.3 (MapLibre Native reads 'json' row and auto-detects gzip)
```
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

class MbtilesWriter(private val db: SQLiteDatabase) {
    init {
        db.execSQL("CREATE TABLE IF NOT EXISTS metadata (name text, value text)")
        db.execSQL("CREATE TABLE IF NOT EXISTS tiles (zoom_level integer, tile_column integer, tile_row integer, tile_data blob)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS tile_index ON tiles (zoom_level, tile_column, tile_row)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS metadata_name ON metadata (name)")
    }
    private val insertTile = db.compileStatement("INSERT OR REPLACE INTO tiles VALUES (?,?,?,?)")

    fun transaction(block: () -> Unit) { db.beginTransaction(); try { block(); db.setTransactionSuccessful() } finally { db.endTransaction() } }

    /** gz = tile bytes exactly as stored in PMTiles (tile_compression 2 = gzip). Do NOT decompress: format 'pbf' means gzip MVT. */
    fun putTile(z: Int, x: Int, y: Int, gz: ByteArray) {
        val tmsRow = (1 shl z) - 1 - y                     // XYZ -> TMS, MapLibre flips back with pow(2,z)-1-y
        insertTile.bindLong(1, z.toLong()); insertTile.bindLong(2, x.toLong())
        insertTile.bindLong(3, tmsRow.toLong()); insertTile.bindBlob(4, gz)
        insertTile.executeInsert()
    }

    private fun setMeta(name: String, value: String) =
        db.execSQL("INSERT OR REPLACE INTO metadata (name, value) VALUES (?,?)", arrayOf(name, value))

    /** pmMetadataJson = decompressed PMTiles metadata JSON; bbox/zooms describe THIS file's contents (union on merge). */
    fun writeMetadata(pmMetadataJson: String, bbox: Bbox, minZ: Int, maxZ: Int) {
        val m = JSONObject(pmMetadataJson)
        setMeta("name", m.optString("name", "Protomaps Basemap"))
        setMeta("format", "pbf")                              // REQUIRED; 'pbf' == gzip-compressed MVT per MBTiles 1.3
        setMeta("minzoom", minZ.toString())
        setMeta("maxzoom", maxZ.toString())
        setMeta("bounds", "${bbox.minLon},${bbox.minLat},${bbox.maxLon},${bbox.maxLat}")
        setMeta("center", "${(bbox.minLon + bbox.maxLon) / 2},${(bbox.minLat + bbox.maxLat) / 2},${minOf(maxZ, 12)}")
        // optional rows straight from the PMTiles metadata (python converter copies every non-layer key)
        for (k in listOf("attribution", "description", "type", "version")) if (m.has(k)) setMeta(k, m.getString(k))
        // REQUIRED for pbf: 'json' row with vector_layers (+ tilestats if present); MapLibre parses this row
        val json = JSONObject()
        if (m.has("vector_layers")) json.put("vector_layers", m.getJSONArray("vector_layers"))
        if (m.has("tilestats")) json.put("tilestats", m.getJSONObject("tilestats"))
        setMeta("json", json.toString())
    }
}
// Planet metadata keys present: vector_layers, attribution, description, name, type ('baselayer'), version ('4.15.2'), planetiler:* and pgf:* strings.
```

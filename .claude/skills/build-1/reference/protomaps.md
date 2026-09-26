# protomaps

## Recommendation
Use Protomaps daily builds as the data source: in a GitHub Actions job install go-pmtiles v1.31.2 from the Linux_x86_64 release tarball, pick the newest key from https://build-metadata.protomaps.dev/builds.json (currently 20260907.pmtiles, tiles 4.15.2, ~138 GB, z0-15, ODbL Produced Work, free to extract), and run `pmtiles extract <url> region.pmtiles --bbox=minLon,minLat,maxLon,maxLat --maxzoom=15` (dry-run first to log size; expect roughly 100-400 MB for 100x100 km of dense Europe, ~1-2 GB for 300x300 km, 2-4 GB for a small country — measure, these are extrapolated). Keep PMTiles (no MBTiles conversion available in the CLI; tile-join if truly needed). Generate the style at build time with @protomaps/basemaps 5.7.2 (`layers("protomaps", namedFlavor("dark"), {lang:"en"})`), point glyphs/sprite at asset:// paths, and bundle the OFL-licensed Noto Sans Regular/Medium/Italic glyph PBFs (all 256 ranges ≈ 11 MB is the safe choice; a Latin-only subset ≈ 1.5 MB works for Latin regions) plus sprites/v4/dark(@2x). Style code is BSD-3-Clause; show '© OpenStreetMap contributors' on the map corner with the URL openstreetmap.org/copyright in an About screen, optionally 'Protomaps © OpenStreetMap'. Pin both the build date and npm version in CI and watch the basemaps CHANGELOG for any 'Tiles 5.0.0' schema bump. Do not bulk-download OSM raster tiles (explicitly prohibited by the OSMF tile policy); OpenFreeMap's weekly planet tiles.pmtiles (~86 GB, OpenMapTiles schema) or Geofabrik+tilemaker are viable fallbacks but need a different (OpenMapTiles) style.

## Facts
- [verified] Protomaps daily planet builds are hosted at https://build.protomaps.com/<YYYYMMDD>.pmtiles (e.g. https://build.protomaps.com/20260907.pmtiles) and listed via the JSON index https://build-metadata.protomaps.dev/builds.json (fields: key, size, md5sum, b3sum, uploaded, version). The HTML page https://maps.protomaps.com/builds/ is a JS app that reads that JSON.
  src: https://build-metadata.protomaps.dev/builds.json
- [verified] Latest planet build on 2026-09-07 is 20260907.pmtiles, 137,838,277,849 bytes (~137.8 GB, ~128 GiB), tileset version 4.15.2, uploaded 2026-09-07T08:50:51Z. Recent builds: 20260901..20260906 are all version 4.15.2 (~137.7 GB); 20260811 = 4.15.1; 20260722 = 4.15.0; 20260720 = 4.14.11. HEAD on 20260906.pmtiles returned content-length 137823988466.
  src: https://build-metadata.protomaps.dev/builds.json
- [verified] Docs state the full planet file 'is roughly 120 gigabytes, including zoom levels from 0 to 15' (actual current size is ~138 GB); max zoom of the basemap tileset is 15; each additional zoom level roughly doubles the file size.
  src: https://docs.protomaps.com/basemaps/downloads
- [verified] Build retention: 'All builds for the past week' plus 'The latest build for each patch version (e.g. 4.3.0)'. Docs warn 'URLs may change' and hotlinking to the downloads is discouraged; copy the tileset to your own storage. A Source Cooperative (AWS us-west-2) mirror of the most recent daily build exists (repository protomaps/openstreetmap).
  src: https://docs.protomaps.com/basemaps/downloads
- [verified] License of the planet builds: 'distributed as an Open Database License Produced Work (OpenStreetMap attribution required)'. The Protomaps homepage says 'Download the planet for free, or extract any part using the CLI.' No fee or registration; hosted API is a separate product (noncommercial free, commercial via GitHub Sponsors).
  src: https://protomaps.com/
- [verified] LICENSE_DATA.md in protomaps/basemaps: OpenStreetMap is ODbL (share-alike, attribution required, example '© OpenStreetMap'); osmdata.openstreetmap.de coastline data is also ODbL; Natural Earth (used for z0-8) is public domain and needs no attribution.
  src: https://raw.githubusercontent.com/protomaps/basemaps/main/LICENSE_DATA.md
- [verified] go-pmtiles latest release is v1.31.2, published 2026-07-22. Linux x86_64 asset: https://github.com/protomaps/go-pmtiles/releases/download/v1.31.2/go-pmtiles_1.31.2_Linux_x86_64.tar.gz (17,444,324 bytes). Also Linux_arm64.tar.gz, Darwin_{arm64,x86_64}.zip (note hyphen: go-pmtiles-1.31.2_Darwin_arm64.zip), Windows_{arm64,x86_64}.zip. License BSD-3-Clause (Copyright 2021 Protomaps LLC).
  src: https://api.github.com/repos/protomaps/go-pmtiles/releases/latest
- [likely] go-pmtiles module path is github.com/protomaps/go-pmtiles (go 1.25.0), root package is a command (pkg.go.dev shows 'command module'), so `go install github.com/protomaps/go-pmtiles@latest` produces a `go-pmtiles` binary (binary name equals module basename, not `pmtiles`). Official docs only point to Releases/Docker (docker image protomaps/go-pmtiles) and Homebrew (`brew install pmtiles`).
  src: https://pkg.go.dev/github.com/protomaps/go-pmtiles
- [verified] pmtiles extract exact syntax (from `pmtiles extract --help`, v1.31.2): `pmtiles extract <input> <output>` with flags --bucket=STRING, --region=STRING (local GeoJSON Polygon or MultiPolygon file), --bbox=STRING ('min_lon,min_lat,max_lon,max_lat'), --minzoom=-1, --maxzoom=-1 (inclusive), --download-threads=4, --dry-run, --overfetch=0.05. Input may be a local file or https URL. Docs form: `pmtiles extract INPUT.pmtiles OUTPUT.pmtiles --bbox=MIN_LON,MIN_LAT,MAX_LON,MAX_LAT`. Source archive must be clustered (planet builds are). Region and bbox are mutually exclusive.
  src: https://docs.protomaps.com/pmtiles/cli
- [verified] Official docs example: `pmtiles extract https://build.protomaps.com/20260730.pmtiles my_area.pmtiles --bbox=4.742883,51.830755,5.552837,52.256198` and `pmtiles extract https://build.protomaps.com/20260730.pmtiles planet_z6.pmtiles --maxzoom=6` (planet z0-6 is ~60 MB).
  src: https://docs.protomaps.com/guide/getting-started
- [verified] `pmtiles convert` is one-directional: 'Convert an MBTiles database to PMTiles' (`pmtiles convert INPUT.mbtiles OUTPUT.pmtiles`, flags --force, --no-deduplication, --tmpdir). pmtiles/convert.go Convert() only calls convertMbtiles(). PMTiles -> MBTiles is NOT supported by the pmtiles CLI; use tippecanoe's tile-join: `tile-join -o out.mbtiles in.pmtiles`.
  src: https://raw.githubusercontent.com/protomaps/go-pmtiles/main/pmtiles/convert.go
- [verified] Published extract sizes: Berlin bbox 13.088348,52.338245,13.761159,52.675508 (~46x38 km, dense city) full z0-15 = 84 MB (bbox) / 68 MB (GeoJSON region), took ~26 s; same region --maxzoom=6 = 813 kB (build 20250514). Half Moon Bay tiny bbox (~17x22 km) = 2 MB. Puglia bbox 14.952521,39.718345,18.632941,42.121209 (~310x270 km, mostly sea) --maxzoom=14 ≈ 200 MB (build 20240917). Waiheke Island small bbox z10-16 typically 20-60 MB.
  src: https://pyviz-tutorial.readthedocs.io/en/latest/protomaps/extract.html
- [unverified] Rough estimates (extrapolated from the above densities, not measured): dense Western-European 100x100 km at z15 ≈ 150-400 MB; 300x300 km mixed at z15 ≈ 0.8-2 GB; a whole small dense country (e.g. Netherlands ~41k km² land) at z15 ≈ 2-4 GB; using --maxzoom=14 roughly halves these. Live --dry-run measurement failed in this sandbox (Go TLS trust of the proxy), so treat as order-of-magnitude only.
  src: https://www.antoniogioia.com/protomaps-open-source-single-file-maps
- [verified] @protomaps/basemaps on npm: dist-tags.latest = 5.7.2 (published 2026-03-10); prior: 5.7.1 (2026-02-27), 5.7.0 (2025-10-31), 5.6.0, 5.5.2, 5.5.1, 5.5.0, 5.4.1, 5.4.0, 5.3.0. License BSD-3-Clause; repo https://github.com/protomaps/basemaps (styles/ subdir); ESM + CJS builds; bin `generate_style`.
  src: https://registry.npmjs.org/@protomaps/basemaps
- [verified] @protomaps/basemaps v5 API (styles/src/index.ts): `export function layers(source: string, flavor: Flavor, options?: { labelsOnly?: boolean; lang?: string }): LayerSpecification[]`; `export function namedFlavor(name: string): Flavor` accepting exactly 'light' | 'dark' | 'white' | 'grayscale' | 'black' (throws 'Flavor not found' otherwise); also exports constants LIGHT, DARK, WHITE, GRAYSCALE, BLACK, types Flavor/Pois, and language_script_pairs, get_multiline_name, get_country_name. If options.lang is omitted, NO label layers are emitted (only nolabels layers).
  src: https://raw.githubusercontent.com/protomaps/basemaps/main/styles/src/index.ts
- [verified] Flavor interface has optional `regular?`, `bold?`, `italic?` fontstack overrides plus `pois?` and `landcover?`. Defaults in base_layers.ts: text-font [t.regular || 'Noto Sans Regular'], [t.bold || 'Noto Sans Medium'], [t.italic || 'Noto Sans Italic']. None of the five built-in flavors override fonts. Devanagari-script labels use fontstack 'Noto Sans Devanagari Regular v1' with pgf:name* properties (language.ts).
  src: https://raw.githubusercontent.com/protomaps/basemaps/main/styles/src/base_layers.ts
- [verified] Flavor descriptions: light and dark are 'general-purpose basemap with icons'; white, grayscale, black are 'for data visualization'. generate_style.ts only sets a sprite URL automatically for light and dark (https://protomaps.github.io/basemaps-assets/sprites/v4/<flavor>); default glyphs URL is https://protomaps.github.io/basemaps-assets/fonts/{fontstack}/{range}.pbf; default attribution HTML: '<a href="https://github.com/protomaps/basemaps">Protomaps</a> © <a href="https://osm.org/copyright">OpenStreetMap</a>'. CLI usage: `generate-style OUTPUT TILEJSON_URL [FLAVOR.js|FLAVOR.ts|FLAVOR.json|FLAVOR_NAME] LANG [SPRITE_URL] [GLYPHS_URL]` (npm run generate_style style.json https://example.com/tilejson.json light en).
  src: https://raw.githubusercontent.com/protomaps/basemaps/main/styles/src/generate_style.ts
- [verified] A generated dark style (npm-style.protomaps.dev/style.json?version=5.7.2&theme=dark&lang=en) has 71 layers using source-layers: boundaries, buildings, earth, landcover, landuse, places, pois, roads, water (tileset v4 also documents a transit layer). Fontstacks referenced: Noto Sans Regular, Noto Sans Medium, Noto Sans Italic, Noto Sans Devanagari Regular v1.
  src: https://npm-style.protomaps.dev/style.json?version=5.7.2&theme=dark&lang=en
- [verified] Version compatibility: tile schema is 'Version 4' (current tiles 4.15.2). Docs: the v4 daily build channel 'is compatible with @protomaps/basemaps style v4.0.0 and newer' — i.e. styles 4.x and 5.x both target tile schema v4. Styles v5.0.0 was a breaking change to the npm package API (renamed package, unified layers() function), not a new tile schema. Tiles and styles are versioned separately in CHANGELOG.md ('Tiles 4.15.2', 'Styles 5.7.2'); SEMANTIC-VERSIONING.md defines what bumps tile MAJOR/MINOR/PATCH (e.g. removing/renaming a common layer or property = MAJOR).
  src: https://docs.protomaps.com/basemaps/downloads
- [verified] protomaps/basemaps-assets layout: fonts.json (['Noto Sans Italic','Noto Sans Medium','Noto Sans Regular']); fonts/{Noto Sans Regular, Noto Sans Medium, Noto Sans Italic, Noto Sans Devanagari Regular v1}/<start>-<end>.pbf — 256 range files each (0-255.pbf ... 65280-65535.pbf); fonts/OFL.txt; sprites/v3/ (legacy) and sprites/v4/{light,dark,white,grayscale,black}[@2x].{json,png} (20 files, ~150 KB total; dark.json has 53 icons incl. POIs and highway shields); scripts/; README.md.
  src: https://api.github.com/repos/protomaps/basemaps-assets/git/trees/main?recursive=1
- [verified] Fonts directory sizes (sum of blob sizes from the git tree): Noto Sans Regular 6.24 MB, Noto Sans Medium 3.61 MB, Noto Sans Italic 1.22 MB, Noto Sans Devanagari Regular v1 0.40 MB — total ≈ 11.5 MB across 1,024 PBF files. The five 'Latin-ish' ranges of Noto Sans Regular (0-255, 256-511, 512-767, 768-1023, 8192-8447) total only ~440 KB.
  src: https://api.github.com/repos/protomaps/basemaps-assets/git/trees/main?recursive=1
- [verified] Fonts license: SIL Open Font License 1.1 ('Copyright 2022 The Noto Project Authors'); sprites are 'derived from MIT-licensed tangrams/icons'. Glyphs were generated with protomaps/font-maker.
  src: https://raw.githubusercontent.com/protomaps/basemaps-assets/main/fonts/OFL.txt
- [verified] Style/code license: protomaps/basemaps LICENSE.md is BSD 3-Clause ('Copyright 2019-2023 Protomaps LLC, Kelso Cartography'); README: 'All code is BSD-3', map design released under CC0, tilesets are ODbL Produced Works. Attribution: '© OpenStreetMap' is mandatory for maps using the OSM tileset; a Protomaps shout-out is optional if using the unmodified basemap style (suggested 'Protomaps © OpenStreetMap'); modified forks must not use the Protomaps branding.
  src: https://raw.githubusercontent.com/protomaps/basemaps/main/LICENSE.md
- [verified] OSMF Attribution Guidelines: attribution must be to 'OpenStreetMap'; '© OpenStreetMap contributors' or '© OpenStreetMap' are acceptable. For a browsable map in an application the credit should appear in a corner of the map; it may be collapsed after interaction or after ~5 seconds as long as the licence info remains reachable via an info button/menu. It must make clear the data is under ODbL, e.g. by linking 'OpenStreetMap' to https://www.openstreetmap.org/copyright (in an offline app, show the URL as text / in an About screen).
  src: https://osmfoundation.org/wiki/Licence/Attribution_Guidelines
- [verified] OSM raster tile bulk download is prohibited. OSMF Tile Usage Policy: 'Bulk downloading is any pre-emptive fetching of tiles other than those a user is actively viewing'; 'Offline use is not permitted on tile.openstreetmap.org'; features like 'Download city/country for offline use' are prohibited; building tile archives (.zip/.mbtiles) for distribution is prohibited.
  src: https://operations.osmfoundation.org/policies/tiles/
- [verified] OpenFreeMap (OpenMapTiles schema, MIT-licensed project, tiles ODbL/OSM) publishes weekly full-planet files at https://btrfs.openfreemap.com/areas/planet/{version}/ — current 20260906_080001_pt (OSM date 2026-08-30): tiles.pmtiles 86,401,568,914 bytes (~86 GB), tiles.mbtiles 102,351,060,992 bytes, tiles.btrfs.gz 97,584,842,045 bytes; index at https://btrfs.openfreemap.com/files.txt. No regional extracts are offered (but you can `pmtiles extract` from tiles.pmtiles). Required attribution: 'OpenFreeMap © OpenMapTiles Data from OpenStreetMap'.
  src: https://btrfs.openfreemap.com/files.txt
- [verified] Alternative pipeline: Geofabrik daily regional .osm.pbf extracts (ODbL, updated ~21:00 CET daily, no login) + tilemaker (FTWPL license) which 'creates vector tiles (in Mapbox Vector Tile format) from an .osm.pbf planet extract' outputting .mbtiles or .pmtiles; keeps data in RAM by default (--store for SSD spill), needs ~2 GB coastline/landcover downloads; produces OpenMapTiles-schema tiles by default, so it needs an OpenMapTiles-compatible style rather than @protomaps/basemaps.
  src: https://raw.githubusercontent.com/systemed/tilemaker/master/README.md
- [verified] Known go-pmtiles issue #225: extracting a very large bbox with --maxzoom (e.g. --bbox=-180,-60,180,85 --maxzoom=5) failed with 'Failed to extract, EOF'; workaround `--overfetch=0`.
  src: https://github.com/protomaps/go-pmtiles/issues/225
- [verified] Protomaps localization: 41 languages supported via `lang`; Devanagari (hi/mr/ne) needs the positioned-glyph fontstack; Arabic/Hebrew need the MapLibre RTL plugin; several South Asian scripts unsupported by MapLibre.
  src: https://docs.protomaps.com/basemaps/localization

## Gotchas
- Planet build file is now ~138 GB (docs still say 'roughly 120 GB'); you never download it — `pmtiles extract` against the https URL does HTTP range requests and only fetches the tiles inside the bbox plus directory overhead.
- Release asset naming differs by OS: Linux/Windows use underscores (go-pmtiles_1.31.2_Linux_x86_64.tar.gz), macOS uses a hyphen after the name (go-pmtiles-1.31.2_Darwin_arm64.zip). The archive contains a single binary named `pmtiles` at the root.
- `go install github.com/protomaps/go-pmtiles@latest` works (root is package main) but yields a binary named `go-pmtiles`, not `pmtiles`. Prefer the release tarball on GitHub Actions (faster, no Go toolchain).
- `pmtiles convert` only goes MBTiles -> PMTiles. For PMTiles -> MBTiles use tippecanoe `tile-join -o out.mbtiles in.pmtiles` (apt: tippecanoe is not in Ubuntu repos; build from source or use a container).
- Build URLs are date-keyed and 'URLs may change'; only the last week of dailies plus the latest per patch version are retained. Pick the build key from builds.json at CI time (or pin a date and accept it may disappear after ~1 week unless it is the last of a patch version).
- Do NOT hotlink build.protomaps.com from the app; the app has no internet anyway, but for CI it's fine to extract from it. Keep the extract in a GitHub Release asset (2 GB per-file limit on GitHub Releases) or split by region/maxzoom.
- `layers()` emits NO label layers unless you pass `{lang: '...'}`; always pass lang.
- The dark style references four fontstacks: 'Noto Sans Regular', 'Noto Sans Medium', 'Noto Sans Italic' and (conditionally, for Devanagari script) 'Noto Sans Devanagari Regular v1'. MapLibre only requests a glyph range PBF when a rendered label needs codepoints in that range, so for a Latin-script region you can ship a subset (roughly ranges 0-255, 256-511, 512-767, 768-1023, 8192-8447 for each of Regular/Medium/Italic ≈ 1-1.5 MB). Missing ranges cause glyph-load errors and blank/partial labels, so shipping the full three fontstacks (~11 MB) is the safe choice; Devanagari can be omitted for European regions.
- Sprites: only light and dark flavors have icon sprites by default (sprites/v4/light|dark); white/grayscale/black sprite JSONs are minimal (shields only). Ship both dark.json/dark.png and dark@2x.json/dark@2x.png; MapLibre appends '@2x' for hi-DPI automatically.
- Tile schema v4 vs styles 5.x: fine — 'compatible with @protomaps/basemaps style v4.0.0 and newer'. Watch the CHANGELOG for a future 'Tiles 5.0.0' which would require a matching style bump; pin both the build date and the npm version in CI.
- Extracting large bboxes with a small --maxzoom can hit 'Failed to extract, EOF' (issue #225); retry with --overfetch=0.
- OSM ODbL attribution must be visible on the map (corner), may collapse after interaction/5 s but must remain reachable from an info button, and must point to openstreetmap.org/copyright (as plain text URL since the app is offline). Protomaps credit is optional but polite: 'Protomaps © OpenStreetMap'.
- Bulk-downloading raster tiles from tile.openstreetmap.org for offline use is explicitly forbidden by the OSMF tile policy — vector extracts from Protomaps/OpenFreeMap/tilemaker are the legitimate route.
- Size figures for 100x100 km / 300x300 km / small-country extracts are extrapolations (Berlin 84 MB at z15, Puglia ~200 MB at z14); run `pmtiles extract ... --dry-run` in CI to get the exact byte count before committing to a distribution plan.
- On ubuntu GitHub Actions runners the extract of a country at z15 (1-4 GB) can take several minutes and needs disk on the runner (~14 GB free on ubuntu-latest); consider --maxzoom=14 for very large regions.

## Snippets
### GitHub Actions bash step: install pmtiles v1.31.2 on ubuntu runner, resolve the latest daily planet build, extract a bbox (dry-run first to log size), verify
```
# .github/workflows/map.yml (step: run)
set -euo pipefail
PMTILES_VERSION=1.31.2
curl -fsSL -o pmtiles.tar.gz \
  "https://github.com/protomaps/go-pmtiles/releases/download/v${PMTILES_VERSION}/go-pmtiles_${PMTILES_VERSION}_Linux_x86_64.tar.gz"
tar -xzf pmtiles.tar.gz pmtiles
sudo install -m 0755 pmtiles /usr/local/bin/pmtiles
pmtiles --help >/dev/null

# Latest daily build key (YYYYMMDD.pmtiles); or pin e.g. BUILD=20260907.pmtiles
BUILD=$(curl -fsSL https://build-metadata.protomaps.dev/builds.json | jq -r 'map(.key) | sort | last')
PLANET="https://build.protomaps.com/${BUILD}"
echo "Using planet build ${PLANET}"

# bbox = min_lon,min_lat,max_lon,max_lat  (example: ~100x100 km around Utrecht)
BBOX="${BBOX:-4.6,51.75,6.05,52.65}"
MAXZOOM="${MAXZOOM:-15}"

# Log what will be fetched without downloading tiles
pmtiles extract "$PLANET" region.pmtiles --bbox="$BBOX" --maxzoom="$MAXZOOM" --dry-run

# Real extract (add --overfetch=0 if you hit 'Failed to extract, EOF' on huge bboxes)
pmtiles extract "$PLANET" region.pmtiles --bbox="$BBOX" --maxzoom="$MAXZOOM" --download-threads=8
# Alternative: polygon region instead of bbox
#   pmtiles extract "$PLANET" region.pmtiles --region=region.geojson --maxzoom=15

pmtiles verify region.pmtiles
pmtiles show region.pmtiles
ls -l region.pmtiles

# If the client needs MBTiles: pmtiles CLI cannot do PMTiles->MBTiles; use tippecanoe's tile-join:
#   tile-join -o region.mbtiles region.pmtiles
```
### Node (ESM) script generating an offline dark MapLibre style JSON with @protomaps/basemaps 5.7.2: source at a pmtiles:// placeholder, glyphs/sprites under asset://
```
// package.json: { "type": "module", "dependencies": { "@protomaps/basemaps": "5.7.2" } }
// run: node gen-style.mjs > app/src/main/assets/style-dark.json
import { layers, namedFlavor } from "@protomaps/basemaps";

const SOURCE = "protomaps";
const THEME = process.env.THEME ?? "dark";      // light | dark | white | grayscale | black
const LANG  = process.env.LANG_CODE ?? "en";    // required, otherwise no label layers
const PMTILES_URL = process.env.PMTILES_URL ?? "pmtiles://__PMTILES_PATH__"; // replace at runtime with the on-device file URL

const style = {
  version: 8,
  name: `protomaps-${THEME}-offline`,
  // Assets bundled in the APK: app/src/main/assets/fonts/<fontstack>/<range>.pbf and assets/sprites/v4/dark(.json|.png|@2x.json|@2x.png)
  glyphs: "asset://fonts/{fontstack}/{range}.pbf",
  sprite: `asset://sprites/v4/${THEME}`,           // only light & dark have full icon sets
  sources: {
    [SOURCE]: {
      type: "vector",
      url: PMTILES_URL,
      attribution: 'Protomaps © OpenStreetMap contributors (ODbL) — openstreetmap.org/copyright',
    },
  },
  layers: layers(SOURCE, namedFlavor(THEME), { lang: LANG }),
};

process.stdout.write(JSON.stringify(style, null, 2));
```
### Bash: fetch only the glyph ranges and sprites the app needs from basemaps-assets (full three Latin fontstacks ≈ 11 MB, or Latin-only subset ≈ 1.5 MB)
```
set -euo pipefail
BASE=https://raw.githubusercontent.com/protomaps/basemaps-assets/main
OUT=app/src/main/assets
mkdir -p "$OUT/sprites/v4"
for f in dark.json dark.png dark@2x.json dark@2x.png; do
  curl -fsSL -o "$OUT/sprites/v4/$f" "$BASE/sprites/v4/$f"
done
curl -fsSL -o "$OUT/fonts/OFL.txt" --create-dirs "$BASE/fonts/OFL.txt"
# Latin-only subset (set FULL=1 to download all 256 ranges per fontstack instead)
RANGES="0-255 256-511 512-767 768-1023 8192-8447"
if [ "${FULL:-0}" = 1 ]; then RANGES=$(seq 0 256 65280 | awk '{print $1"-"($1+255)}'); fi
for stack in "Noto Sans Regular" "Noto Sans Medium" "Noto Sans Italic"; do
  mkdir -p "$OUT/fonts/$stack"
  for r in $RANGES; do
    curl -fsSL -o "$OUT/fonts/$stack/$r.pbf" "$BASE/fonts/$(printf %s "$stack" | sed 's/ /%20/g')/$r.pbf"
  done
done
du -sh "$OUT/fonts" "$OUT/sprites"
```

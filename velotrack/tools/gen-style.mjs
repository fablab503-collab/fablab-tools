#!/usr/bin/env node
// Generates the offline MapLibre styles bundled in the APK (app/src/main/assets/style.json for the dark
// theme, style_light.json for the light theme) and the vector_layers list used when the app creates
// empty band files (app/src/main/assets/vector_layers.json).
//
// Two flavours, one per run (--flavor):
//   dark   the Protomaps "dark" flavor from @protomaps/basemaps 5.7.2, patched for an OLED screen:
//          pure black background and earth fill.
//   light  the Protomaps "light" flavor with darker road casings and label greys so the map stays
//          legible in sunlight (see docs/superpowers/specs/2026-09-07-velotrack-riding-mode-theme-stats-design.md §3.6).
// POI layers are removed from both. Each flavour references its own sprite sheet (sprites/v4/<flavor>).
//
// Four vector sources, one per detail band (see docs/superpowers/specs/2026-09-07-velotrack-map-download-design.md):
//   band0 z0-6, band1 z7-9, band2 z10-12, band3 z13-15
// Each source carries "url": "{MAP_URL_N}", which the app replaces at runtime with mbtiles:///... for
// the band file (see map/StyleTemplate.kt). The full Protomaps layer set is emitted once per band, in
// order band0 -> band3 so finer bands draw on top; layer ids are suffixed _b0.._b3. A single
// "background" layer comes first. MapLibre draws nothing for a tile a band does not have, but
// overzooms every band past its maxzoom, so coarse bands show through wherever finer data is missing.
// Glyphs and sprites are read from APK assets.
//
// Usage: node gen-style.mjs [output-path] [--flavor dark|light]
//   --flavor defaults to dark.
//   output-path defaults to ../app/src/main/assets/style.json (dark) or style_light.json (light)
//   relative to this script.
//   vector_layers.json is written next to the style output (same content for both flavours).
// Environment:
//   VELOTRACK_LANG        label language code (e.g. en, de, fr, pt, zh-Hans). Falls back to the language
//                         part of the system LANG variable (en_US.UTF-8 -> en); default en.
//   VELOTRACK_NO_NETWORK  when set (non-empty), skip fetching the planet metadata and write the
//                         fallback vector_layers list right away.

import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { gunzipSync } from "node:zlib";
import { layers, namedFlavor, language_script_pairs } from "@protomaps/basemaps";

const FLAVOR_NAMES = ["dark", "light"];
const DEFAULT_FLAVOR = "dark";
const DEFAULT_OUTPUT = { dark: "style.json", light: "style_light.json" };
const BAND_COUNT = 4;
const MAP_URL_PLACEHOLDER_PREFIX = "{MAP_URL_"; // "{MAP_URL_0}" .. "{MAP_URL_3}"
const ATTRIBUTION = "© OpenStreetMap contributors";
const BUNDLED_FONTS = ["Noto Sans Regular", "Noto Sans Medium", "Noto Sans Italic"];

const BUILDS_URL = "https://build-metadata.protomaps.dev/builds.json";
const PLANET_BASE_URL = "https://build.protomaps.com/";
const FETCH_TIMEOUT_MS = 30_000;
const PMTILES_HEADER_LENGTH = 127;
const PMTILES_MAGIC = "PMTiles";
const PMTILES_VERSION = 3;
const PMTILES_COMPRESSION_NONE = 1;
const PMTILES_COMPRESSION_GZIP = 2;

// Used when the planet metadata cannot be fetched. Layer names of the Protomaps 4.x tile schema.
const FALLBACK_VECTOR_LAYERS = [
  "boundaries",
  "buildings",
  "earth",
  "landcover",
  "landuse",
  "places",
  "pois",
  "roads",
  "transit",
  "water",
].map((id) => ({ id, fields: {} }));

function pickLang() {
  const supported = language_script_pairs.map((p) => p.lang);
  const raw = (process.env.VELOTRACK_LANG || process.env.LANG || "").trim();
  if (raw === "") return "en";
  // Exact match first (covers zh-Hans / zh-Hant).
  const exact = supported.find((l) => l.toLowerCase() === raw.toLowerCase());
  if (exact) return exact;
  // Locale forms such as en_US.UTF-8, pt_BR, de-AT, C, POSIX.
  const code = raw.toLowerCase().split(/[_.@-]/)[0];
  if (code === "" || code === "c" || code === "posix") return "en";
  if (!supported.includes(code)) {
    console.warn(`gen-style: language "${code}" is not in the Protomaps list (${supported.join(", ")}); using it anyway, labels fall back to the local name.`);
  }
  return code;
}

// ---------------------------------------------------------------- CLI

/** Parses `[output-path] [--flavor dark|light]`; exits with usage on anything else. */
function parseArgs(argv) {
  let flavorName = DEFAULT_FLAVOR;
  let outArg = null;
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--flavor") {
      if (i + 1 >= argv.length) usage(`--flavor needs a value (${FLAVOR_NAMES.join("|")})`);
      flavorName = argv[++i];
    } else if (a.startsWith("--flavor=")) {
      flavorName = a.slice("--flavor=".length);
    } else if (a.startsWith("--")) {
      usage(`unknown option ${a}`);
    } else if (outArg === null) {
      outArg = a;
    } else {
      usage(`unexpected argument ${a}`);
    }
  }
  if (!FLAVOR_NAMES.includes(flavorName)) usage(`unknown flavor "${flavorName}" (expected ${FLAVOR_NAMES.join("|")})`);
  return { flavorName, outArg };
}

function usage(message) {
  console.error(`gen-style: ${message}`);
  console.error(`usage: node gen-style.mjs [output-path] [--flavor ${FLAVOR_NAMES.join("|")}]`);
  process.exit(2);
}

// ---------------------------------------------------------------- flavours

/**
 * Light flavour overrides for sunlight legibility. Stock LIGHT casings (#e0e0e0 on earth #e2dfda) and
 * mid-grey labels wash out outdoors, so every road casing and label colour is darkened; the highway
 * and major fills get a pale amber tint to keep the hierarchy readable. Halos stay untouched.
 */
const LIGHT_PATCH = {
  minor_casing: "#b8b8b8",
  major_casing_early: "#9a9a9a",
  major_casing_late: "#9a9a9a",
  highway_casing_early: "#8a8a8a",
  highway_casing_late: "#8a8a8a",
  bridges_minor_casing: "#b8b8b8",
  bridges_major_casing: "#9a9a9a",
  bridges_highway_casing: "#8a8a8a",
  link_casing: "#a8a8a8",
  minor_service_casing: "#c4c4c4",
  bridges_link_casing: "#a8a8a8",
  bridges_other_casing: "#b8b8b8",
  highway: "#fff2c2",
  major: "#fffbe6",
  bridges_highway: "#fff2c2",
  bridges_major: "#fffbe6",
  roads_label_minor: "#4a4a4a",
  roads_label_major: "#2e2e2e",
  city_label: "#1f1f1f",
  subplace_label: "#4a4a4a",
  state_label: "#7a7a7a",
  country_label: "#5c5c5c",
  address_label: "#4a4a4a",
};

/** The Protomaps flavour object handed to layers() for [flavorName]. */
function flavorFor(flavorName) {
  const base = namedFlavor(flavorName);
  if (flavorName === "light") {
    for (const key of Object.keys(LIGHT_PATCH)) {
      if (!(key in base)) throw new Error(`gen-style: light patch key "${key}" does not exist in the Protomaps flavour`);
    }
    return { ...base, ...LIGHT_PATCH };
  }
  return base;
}

// ---------------------------------------------------------------- style

/**
 * Generates the Protomaps layer set bound to source `band<index>`, with every layer id suffixed
 * `_b<index>`. The dark flavour gets a pure black earth fill (OLED). The background layer is removed
 * (the style has a single one).
 */
// How far past its own zoom range a band is still allowed to draw.
//
// MapLibre overzooms a source past its maxzoom without limit, so every band used to render at every
// zoom: at z14 all four were live, 65 layers each, 261 layers and 44 symbol layers in total. Inside
// a downloaded area the three coarse bands are completely hidden behind band 3's opaque earth fill,
// yet they were still parsed, drawn, and - the expensive part - included in the symbol collision
// pass on every rotation. That is what made panning and rotating cost 2-4x more than it needed to.
//
// The cap cannot simply be each band's own maxZoom: outside the downloaded area the finer band has
// no tiles, and the coarse band showing through is exactly the intended fallback. These cutoffs sit
// a few levels above each band's range, which keeps that fallback where it is useful and drops the
// cases that never were - a z6 world tile stretched across a street-level view.
// Band ranges (download/Bands.kt): 0 = z0-6, 1 = z7-9, 2 = z10-12, 3 = z13-15.
const BAND_MAX_RENDER_ZOOM = [9, 12, 16, null]; // null = the finest band, overzooms freely

function bandLayers(index, lang, flavorName, flavor) {
  const source = `band${index}`;
  const suffix = `_b${index}`;
  let out = layers(source, flavor, { lang });
  // POIs are kept. The tiles already carry the `pois` layer (name, kind, kind_detail, elevation)
  // and the bundled v4 sprite sheet already has the icons for it, so dropping them threw away
  // detail that was downloaded and paid for: cafes, water, bike shops, viewpoints, stations.
  out = out.filter((l) => l.type !== "background");
  let sawEarth = false;
  for (const l of out) {
    if (l.id === "earth") {
      if (flavorName === "dark") l.paint = { ...(l.paint ?? {}), "fill-color": "#000000" };
      sawEarth = true;
    }
    l.id = `${l.id}${suffix}`;
    l.source = source;
    const cutoff = BAND_MAX_RENDER_ZOOM[index];
    if (cutoff !== null && cutoff !== undefined) {
      // Keep whichever limit is tighter; some Protomaps layers already carry a maxzoom.
      l.maxzoom = l.maxzoom === undefined ? cutoff : Math.min(l.maxzoom, cutoff);
      // A layer whose own minzoom is already past the cutoff would be inert; drop it below.
      if (l.minzoom !== undefined && l.minzoom >= l.maxzoom) l.__drop = true;
    }
  }
  out = out.filter((l) => !l.__drop);
  if (!sawEarth) throw new Error(`gen-style: no earth layer in generated style for ${source}`);
  return out;
}

function backgroundLayer(lang, flavorName, flavor) {
  const bg = layers("band0", flavor, { lang }).filter((l) => l.type === "background");
  if (bg.length !== 1) throw new Error(`gen-style: expected exactly one background layer, got ${bg.length}`);
  const l = bg[0];
  if (flavorName === "dark") l.paint = { ...(l.paint ?? {}), "background-color": "#000000" };
  return l;
}

function buildStyle(lang, flavorName) {
  const flavor = flavorFor(flavorName);
  const styleLayers = [backgroundLayer(lang, flavorName, flavor)];
  for (let b = 0; b < BAND_COUNT; b++) {
    styleLayers.push(...bandLayers(b, lang, flavorName, flavor));
  }

  const ids = new Set(styleLayers.map((l) => l.id));
  if (ids.size !== styleLayers.length) throw new Error("gen-style: duplicate layer ids");
  const symbolLayers = styleLayers.filter((l) => l.type === "symbol").length;
  if (symbolLayers === 0) throw new Error("gen-style: no label layers generated (lang option missing?)");

  // Every fontstack referenced literally must be bundled by build-assets.sh. Devanagari is referenced
  // only inside conditional expressions (Hindi/Marathi/Nepali labels) and is intentionally not bundled.
  const KNOWN_UNBUNDLED = ["Noto Sans Devanagari Regular v1"];
  const json = JSON.stringify(styleLayers);
  const fontRefs = new Set([...json.matchAll(/"Noto Sans[^"]*"/g)].map((m) => JSON.parse(m[0])));
  for (const f of fontRefs) {
    if (!BUNDLED_FONTS.includes(f) && !KNOWN_UNBUNDLED.includes(f)) {
      console.warn(`gen-style: fontstack "${f}" is referenced but not bundled; labels needing it will not render.`);
    }
  }

  const sources = {};
  for (let b = 0; b < BAND_COUNT; b++) {
    sources[`band${b}`] = {
      type: "vector",
      url: `${MAP_URL_PLACEHOLDER_PREFIX}${b}}`,
      attribution: ATTRIBUTION,
    };
  }

  const style = {
    version: 8,
    name: `velotrack-${flavorName}`,
    metadata: {
      "velotrack:generator": "@protomaps/basemaps 5.7.2",
      "velotrack:flavor": flavorName,
      "velotrack:lang": lang,
      "velotrack:bands": BAND_COUNT,
    },
    glyphs: "asset://fonts/{fontstack}/{range}.pbf",
    sprite: `asset://sprites/v4/${flavorName}`,
    sources,
    layers: styleLayers,
  };
  return { style, symbolLayers };
}

// ---------------------------------------------------------------- vector_layers from the planet

async function fetchWithTimeout(url, init = {}) {
  const res = await fetch(url, { ...init, signal: AbortSignal.timeout(FETCH_TIMEOUT_MS) });
  return res;
}

/** GET bytes [first, last] (inclusive) of `url`; requires a 206 response of exactly that length. */
async function fetchRange(url, first, last) {
  const res = await fetchWithTimeout(url, { headers: { Range: `bytes=${first}-${last}` } });
  if (res.status !== 206) throw new Error(`range request ${first}-${last} returned HTTP ${res.status}`);
  const body = new Uint8Array(await res.arrayBuffer());
  const expected = Number(BigInt(last) - BigInt(first) + 1n);
  if (body.length !== expected) throw new Error(`range request ${first}-${last} returned ${body.length} bytes, expected ${expected}`);
  return body;
}

/** Newest build key (YYYYMMDD.pmtiles) listed in builds.json. */
async function latestBuildKey() {
  const res = await fetchWithTimeout(BUILDS_URL);
  if (!res.ok) throw new Error(`${BUILDS_URL} returned HTTP ${res.status}`);
  const builds = await res.json();
  if (!Array.isArray(builds)) throw new Error("builds.json is not an array");
  const keys = builds
    .map((b) => (b && typeof b.key === "string" ? b.key : null))
    .filter((k) => k !== null && /^\d{8}\.pmtiles$/.test(k))
    .sort();
  if (keys.length === 0) throw new Error("builds.json lists no YYYYMMDD.pmtiles build");
  return keys[keys.length - 1];
}

/** Reads the PMTiles v3 header, range-reads the JSON metadata blob and returns its vector_layers array. */
async function fetchVectorLayers(planetUrl) {
  const header = await fetchRange(planetUrl, 0, PMTILES_HEADER_LENGTH - 1);
  const magic = Buffer.from(header.subarray(0, 7)).toString("latin1");
  if (magic !== PMTILES_MAGIC) throw new Error(`not a PMTiles archive (magic "${magic}")`);
  if (header[7] !== PMTILES_VERSION) throw new Error(`unsupported PMTiles version ${header[7]}`);
  const view = new DataView(header.buffer, header.byteOffset, header.byteLength);
  const metadataOffset = view.getBigUint64(24, true);
  const metadataLength = view.getBigUint64(32, true);
  const internalCompression = header[97];
  if (metadataLength === 0n) throw new Error("archive has no metadata");
  if (metadataLength > 64n * 1024n * 1024n) throw new Error(`metadata blob is implausibly large (${metadataLength} bytes)`);

  let blob = await fetchRange(planetUrl, metadataOffset, metadataOffset + metadataLength - 1n);
  if (internalCompression === PMTILES_COMPRESSION_GZIP) {
    blob = new Uint8Array(gunzipSync(blob));
  } else if (internalCompression !== PMTILES_COMPRESSION_NONE) {
    throw new Error(`unsupported internal compression ${internalCompression}`);
  }
  const metadata = JSON.parse(Buffer.from(blob).toString("utf8"));
  const vectorLayers = metadata?.vector_layers;
  if (!Array.isArray(vectorLayers) || vectorLayers.length === 0) throw new Error("metadata has no vector_layers array");
  for (const vl of vectorLayers) {
    if (!vl || typeof vl.id !== "string" || vl.id === "") throw new Error("vector_layers entry without an id");
    if (vl.fields === undefined) vl.fields = {};
  }
  return vectorLayers;
}

/** Returns { vectorLayers, source } where source names where the list came from. Never throws. */
async function resolveVectorLayers() {
  if ((process.env.VELOTRACK_NO_NETWORK || "").trim() !== "") {
    return { vectorLayers: FALLBACK_VECTOR_LAYERS, source: "fallback (VELOTRACK_NO_NETWORK set)" };
  }
  try {
    const key = await latestBuildKey();
    const planetUrl = `${PLANET_BASE_URL}${key}`;
    const vectorLayers = await fetchVectorLayers(planetUrl);
    return { vectorLayers, source: `planet ${key}` };
  } catch (e) {
    console.warn(`gen-style: could not read vector_layers from the planet (${e?.message ?? e}); using the fallback list.`);
    return { vectorLayers: FALLBACK_VECTOR_LAYERS, source: "fallback" };
  }
}

// ---------------------------------------------------------------- main

const here = dirname(fileURLToPath(import.meta.url));
const { flavorName, outArg } = parseArgs(process.argv.slice(2));
const outPath = resolve(outArg ?? resolve(here, "../app/src/main/assets", DEFAULT_OUTPUT[flavorName]));
const vectorLayersPath = resolve(dirname(outPath), "vector_layers.json");
const lang = pickLang();

const { style, symbolLayers } = buildStyle(lang, flavorName);

mkdirSync(dirname(outPath), { recursive: true });
// Pretty-printed so each source entry reads exactly "url": "{MAP_URL_N}" (see map/StyleTemplate.kt).
writeFileSync(outPath, `${JSON.stringify(style, null, 2)}\n`);
console.log(`gen-style: wrote ${outPath} (${style.layers.length} layers in ${BAND_COUNT} bands, flavor=${flavorName}, lang=${lang}, ${symbolLayers} label layers)`);

const { vectorLayers, source } = await resolveVectorLayers();
writeFileSync(vectorLayersPath, `${JSON.stringify(vectorLayers, null, 2)}\n`);
console.log(`gen-style: wrote ${vectorLayersPath} (${vectorLayers.length} layers from ${source})`);

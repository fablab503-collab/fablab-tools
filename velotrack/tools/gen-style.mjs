#!/usr/bin/env node
// Generates the offline MapLibre style bundled in the APK (app/src/main/assets/style.json) and the
// vector_layers list used when the app creates empty band files (app/src/main/assets/vector_layers.json).
//
// The style is the Protomaps "dark" flavor from @protomaps/basemaps 5.7.2, patched for an OLED
// screen: pure black background and earth fill, POI layers removed.
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
// Usage: node gen-style.mjs [output-path]
//   output-path defaults to ../app/src/main/assets/style.json relative to this script.
//   vector_layers.json is written next to the style output.
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

const FLAVOR = "dark";
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

// ---------------------------------------------------------------- style

/**
 * Generates the Protomaps layer set bound to source `band<index>`, patched for OLED, with every
 * layer id suffixed `_b<index>`. The background layer is removed (the style has a single one).
 */
function bandLayers(index, lang) {
  const source = `band${index}`;
  const suffix = `_b${index}`;
  let out = layers(source, namedFlavor(FLAVOR), { lang });
  out = out.filter((l) => l.type !== "background" && !l.id.startsWith("pois"));
  let sawEarth = false;
  for (const l of out) {
    if (l.id === "earth") {
      l.paint = { ...(l.paint ?? {}), "fill-color": "#000000" };
      sawEarth = true;
    }
    l.id = `${l.id}${suffix}`;
    l.source = source;
  }
  if (!sawEarth) throw new Error(`gen-style: no earth layer in generated style for ${source}`);
  return out;
}

function backgroundLayer(lang) {
  const bg = layers("band0", namedFlavor(FLAVOR), { lang }).filter((l) => l.type === "background");
  if (bg.length !== 1) throw new Error(`gen-style: expected exactly one background layer, got ${bg.length}`);
  const l = bg[0];
  l.paint = { ...(l.paint ?? {}), "background-color": "#000000" };
  return l;
}

function buildStyle(lang) {
  const styleLayers = [backgroundLayer(lang)];
  for (let b = 0; b < BAND_COUNT; b++) {
    styleLayers.push(...bandLayers(b, lang));
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
    name: "velotrack-dark",
    metadata: {
      "velotrack:generator": "@protomaps/basemaps 5.7.2",
      "velotrack:flavor": FLAVOR,
      "velotrack:lang": lang,
      "velotrack:bands": BAND_COUNT,
    },
    glyphs: "asset://fonts/{fontstack}/{range}.pbf",
    sprite: `asset://sprites/v4/${FLAVOR}`,
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
const outPath = resolve(process.argv[2] ?? resolve(here, "../app/src/main/assets/style.json"));
const vectorLayersPath = resolve(dirname(outPath), "vector_layers.json");
const lang = pickLang();

const { style, symbolLayers } = buildStyle(lang);

mkdirSync(dirname(outPath), { recursive: true });
// Pretty-printed so each source entry reads exactly "url": "{MAP_URL_N}" (see map/StyleTemplate.kt).
writeFileSync(outPath, `${JSON.stringify(style, null, 2)}\n`);
console.log(`gen-style: wrote ${outPath} (${style.layers.length} layers in ${BAND_COUNT} bands, lang=${lang}, ${symbolLayers} label layers)`);

const { vectorLayers, source } = await resolveVectorLayers();
writeFileSync(vectorLayersPath, `${JSON.stringify(vectorLayers, null, 2)}\n`);
console.log(`gen-style: wrote ${vectorLayersPath} (${vectorLayers.length} layers from ${source})`);

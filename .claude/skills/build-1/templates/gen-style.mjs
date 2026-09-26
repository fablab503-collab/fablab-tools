#!/usr/bin/env node
// Generates the offline MapLibre style bundled in the APK (app/src/main/assets/style.json).
//
// The style is the Protomaps "dark" flavor from @protomaps/basemaps 5.7.2, patched for an OLED
// screen: pure black background and earth fill, POI layers removed. The single vector source has
// "url": "{MAP_URL}", which the app replaces at runtime with mbtiles:///... or pmtiles://file:///...
// (see map/StyleTemplate.kt). Glyphs and sprites are read from APK assets.
//
// Usage: node gen-style.mjs [output-path]
//   output-path defaults to ../app/src/main/assets/style.json relative to this script.
// Environment:
//   VELOTRACK_LANG  label language code (e.g. en, de, fr, pt, zh-Hans). Falls back to the language
//                   part of the system LANG variable (en_US.UTF-8 -> en); default en.

import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { layers, namedFlavor, language_script_pairs } from "@protomaps/basemaps";

const SOURCE = "protomaps";
const FLAVOR = "dark";
const MAP_URL_PLACEHOLDER = "{MAP_URL}";
const BUNDLED_FONTS = ["Noto Sans Regular", "Noto Sans Medium", "Noto Sans Italic"];

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

const here = dirname(fileURLToPath(import.meta.url));
const outPath = resolve(process.argv[2] ?? resolve(here, "../app/src/main/assets/style.json"));
const lang = pickLang();

let styleLayers = layers(SOURCE, namedFlavor(FLAVOR), { lang });

// Patches for OLED and battery.
styleLayers = styleLayers.filter((l) => !l.id.startsWith("pois"));
let sawBackground = false;
let sawEarth = false;
for (const l of styleLayers) {
  if (l.type === "background") {
    l.paint = { ...(l.paint ?? {}), "background-color": "#000000" };
    sawBackground = true;
  }
  if (l.id === "earth") {
    l.paint = { ...(l.paint ?? {}), "fill-color": "#000000" };
    sawEarth = true;
  }
}
if (!sawBackground) throw new Error("gen-style: no background layer in generated style");
if (!sawEarth) throw new Error("gen-style: no earth layer in generated style");
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

const style = {
  version: 8,
  name: "velotrack-dark",
  metadata: {
    "velotrack:generator": "@protomaps/basemaps 5.7.2",
    "velotrack:flavor": FLAVOR,
    "velotrack:lang": lang,
  },
  glyphs: "asset://fonts/{fontstack}/{range}.pbf",
  sprite: `asset://sprites/v4/${FLAVOR}`,
  sources: {
    [SOURCE]: {
      type: "vector",
      url: MAP_URL_PLACEHOLDER,
      attribution: "© OpenStreetMap contributors",
    },
  },
  layers: styleLayers,
};

mkdirSync(dirname(outPath), { recursive: true });
// Pretty-printed so the source entry reads exactly "url": "{MAP_URL}" (see map/StyleTemplate.kt).
writeFileSync(outPath, `${JSON.stringify(style, null, 2)}\n`);
console.log(`gen-style: wrote ${outPath} (${styleLayers.length} layers, lang=${lang}, ${symbolLayers} label layers)`);

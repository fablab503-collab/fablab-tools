# Third-party software and data in VeloTrack

VeloTrack's own code is released under the MIT licence of this repository. The app bundles or
depends on the following third-party work.

## Map data

**OpenStreetMap** — the map tiles you extract with the `velotrack-map-extract` workflow contain
OpenStreetMap data, (c) OpenStreetMap contributors, licensed under the
[Open Data Commons Open Database License (ODbL) 1.0](https://opendatacommons.org/licenses/odbl/1-0/).
Attribution text shown in the app: "© OpenStreetMap contributors" with the URL
https://www.openstreetmap.org/copyright. Zoom levels 0-8 of the Protomaps tileset also use
Natural Earth data (public domain).

**Protomaps planet builds** (https://build.protomaps.com/) — daily PMTiles archives of
OpenStreetMap, distributed as an ODbL Produced Work; they are the source the extract workflow
cuts regions from. https://protomaps.com/

## Map style, fonts and sprites bundled in the APK

**@protomaps/basemaps 5.7.2** (map style generator) — BSD-3-Clause,
Copyright 2019-2023 Protomaps LLC, Kelso Cartography. https://github.com/protomaps/basemaps
The generated styles (`assets/style.json`, `assets/style_light.json`) are modified "dark" and
"light" flavors (black earth for OLED screens, darker road casings and labels in the light style,
POI layers removed) and are not Protomaps-branded.

**Noto Sans** glyphs (`assets/fonts/Noto Sans Regular|Medium|Italic`) — SIL Open Font License 1.1,
Copyright 2022 The Noto Project Authors. The licence text is bundled as `assets/fonts/OFL.txt`.
Glyph PBFs from https://github.com/protomaps/basemaps-assets (generated with protomaps/font-maker).

**Sprites** (`assets/sprites/v4/dark*`, `assets/sprites/v4/light*`) — from
https://github.com/protomaps/basemaps-assets, derived from the MIT-licensed
[tangrams/icons](https://github.com/tangrams/icons).

**Barlow Condensed** (`res/font/barlow_condensed_*.ttf`, the app typeface) — SIL Open Font License
1.1, Copyright 2017 The Barlow Project Authors. https://github.com/jpt/barlow — licence text in
`OFL-Barlow.txt`.

**3dicons** (`res/drawable-nodpi/img3d_*.webp` and the library in `assets/icons3d/` at the
repository root) — CC0 1.0 (public domain dedication) by Vijay Verma. https://3dicons.co/

**Material Symbols** (`res/drawable/ic_*.xml`) — Apache License 2.0, Google.
https://fonts.google.com/icons

## Libraries

**MapLibre Native for Android** (`org.maplibre.gl:android-sdk-opengl` 13.6.0) — BSD-2-Clause,
Copyright (c) MapLibre contributors (originally Mapbox GL Native, Copyright (c) 2014-2020 Mapbox).
https://github.com/maplibre/maplibre-native

**AndroidX** (core-ktx, appcompat, activity, lifecycle, preference, constraintlayout) and
**Material Components for Android** — Apache License 2.0, Copyright The Android Open Source Project.
https://developer.android.com/jetpack/androidx, https://github.com/material-components/material-components-android

**OkHttp** (map download HTTP range requests) — Apache License 2.0, Copyright 2019 Square, Inc.
https://square.github.io/okhttp/

**kotlinx.coroutines** — Apache License 2.0, Copyright 2016-2024 JetBrains s.r.o.
https://github.com/Kotlin/kotlinx.coroutines

**Kotlin standard library** — Apache License 2.0, Copyright 2010-2024 JetBrains s.r.o. and Kotlin
Programming Language contributors.

**JUnit 4** (tests only, not shipped) — Eclipse Public License 1.0. https://junit.org/junit4/

## Build and map tooling (not shipped in the APK)

**go-pmtiles** (`pmtiles` CLI 1.31.2) — BSD-3-Clause, Copyright 2021 Protomaps LLC.
https://github.com/protomaps/go-pmtiles

**pmtiles** Python package 3.7.0 (`pmtiles-convert`) — BSD-3-Clause, Protomaps LLC.
https://github.com/protomaps/PMTiles

## Licence texts

- BSD-2-Clause: https://opensource.org/license/bsd-2-clause
- BSD-3-Clause: https://opensource.org/license/bsd-3-clause
- Apache License 2.0: https://www.apache.org/licenses/LICENSE-2.0
- SIL Open Font License 1.1: https://openfontlicense.org/ (also bundled in `assets/fonts/OFL.txt`)
- MIT: https://opensource.org/license/mit
- ODbL 1.0: https://opendatacommons.org/licenses/odbl/1-0/

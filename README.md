<div align="center">

<img src="docs/assets/banner.png" alt="GhostRoute" width="380" />

# GhostRoute

**Offline, privacy‑first turn‑by‑turn navigation that routes you _around_ ALPR / Flock surveillance cameras.**

[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84?logo=android&logoColor=white)](#)
[![Language](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white)](#)
[![UI](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4)](#)
[![No Google Play Services](https://img.shields.io/badge/Google%20Play%20Services-none-critical)](#privacy)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

</div>

---

GhostRoute is an Android navigation app that works **100% offline** and is built around one idea: get you where you're going while **avoiding automated license‑plate readers** (ALPR), including Flock Safety cameras, gunshot sensors, and speed/red‑light cameras. The map, the search, and the routing all run on‑device — navigation **never touches the network** — and the app ships with **zero Google Play Services, zero Firebase, and zero analytics.**

> ⚠️ **Status: early, single‑region preview (`v0.1`).** Bundled offline data currently covers **Tennessee + Western North Carolina**. It runs on a GrapheneOS Pixel and stock Android 8+. See [Roadmap](#roadmap).

---

## Why

License‑plate readers are spreading fast, and most of them are mapped in the open by the community (e.g. the [DeFlock](https://deflock.me) project, whose data flows into OpenStreetMap). GhostRoute takes that public map of cameras and turns it into something actionable: a router that treats a camera you'd drive past as a *cost*, and looks for a way around it — without ever phoning home about where you are or where you're going.

## Features

- 🛰️ **Camera‑aware routing.** Computes the fastest route **and** camera‑avoiding alternatives, scored by how many readers each one passes. Avoidance is **directional** — a camera only "counts" if it actually reads your direction of travel.
- 🧭 **Waze‑style drive view.** Heading‑up map that rotates to your direction of travel, a live MPH speedometer, a triangular nav arrow, and a turn‑by‑turn banner with voice prompts.
- 🗺️ **Fully offline basemap.** Vector tiles (OpenMapTiles schema) rendered by MapLibre with day/night themes that flip automatically at local sunrise/sunset.
- 🔎 **Offline search.** On‑device SQLite + FTS geocoder over streets, places, and **house‑number addresses**, ranked by relevance and distance from you.
- ⭐ **Favorites & recents.** Home / Work / pinned places with rename + remove, and a draggable bottom sheet you can park anywhere.
- 🔴 **Live camera layer.** Color‑coded by type (ALPR/Flock, gunshot, speed, CCTV), each tappable to show its read direction and how fresh the OSM data is. One‑tap refresh pulls the latest for your area and flashes "Updated — N new devices."
- 🔒 **Privacy by construction.** See below.

## Privacy

Privacy isn't a setting in GhostRoute — it's enforced by the build.

- **No Google Play Services, no Firebase, no analytics, no ad SDKs.** A Gradle check (`:app:verifyNoGms`) **fails the build** if `com.google.android.gms` (or friends) ever sneaks into the dependency graph.
- **Navigation is air‑gapped.** Routing, search, and map rendering read only on‑device data. The app does not need — and does not use — the network while you drive.
- **The only network call** is the optional camera refresh, which sends a single **coarse, tile‑aligned bounding box** (~80 km cell) to a public OpenStreetMap Overpass server. No identity, no precise location, no tracking, no account.
- **No telemetry, ever.** Nothing about your trips, searches, or favorites leaves the device.

## How the camera avoidance works

```
OSM/DeFlock camera nodes ──► directional exposure model ──► penalty weighting ──► A* + landmarks
   (man_made=surveillance,      (which directed edges a       (seconds added per      (fast on‑device
    surveillance:type=ALPR,      reader actually watches,       camera you'd pass)       search; returns
    highway=speed_camera, …)     within radius + bearing)                                ranked alternatives)
```

1. **Map cameras to roads.** Each camera is projected onto nearby road segments; its `direction` tag decides which *directed* edges it actually surveils (so an oncoming‑only reader doesn't penalize your side).
2. **Penalize, don't forbid.** Camera‑watched edges get extra cost (a tunable seconds‑per‑camera penalty). A sweep of penalties yields a spectrum from "fastest" to "fewest cameras."
3. **Offer real choices.** GhostRoute also pulls genuinely distinct corridors and labels them ("Fastest", "Fewer cameras", "Fewest cameras") so you decide the trade‑off.

The same logic runs as a desktop prototype in [`scripts/graphtool`](scripts/graphtool) against the real routing graph, so the engine can be validated off‑device.

## Tech stack

| Area | Choice |
| --- | --- |
| Language / UI | Kotlin, Jetpack Compose (Material 3) |
| Map rendering | MapLibre Native (Android) + offline **PMTiles** |
| Routing | GraphHopper (core) — A* with landmark heuristics, contraction‑hierarchy alternatives |
| Search | SQLite + FTS, with US DOT National Address Database house numbers |
| Storage | Room |
| Camera data | OpenStreetMap via Overpass API (ODbL) |
| Min / target SDK | 26 / 36 |

No third‑party HTTP or JSON libraries — the one network path uses the platform `HttpURLConnection` + `org.json`, keeping the dependency surface (and the no‑GMS guarantee) tight.

## Building from source

GhostRoute's APK is small; the heavy offline data (basemap, routing graph, address index) is **built separately and pushed into app storage** — it's far too large for an APK and is never committed to git.

**Prerequisites:** Android Studio (JDK 21 / JBR), an Android device or emulator, `adb`. The data builders also use Java 17+, Python 3, and `osmium`.

```bash
# 1. Build the app (verifyNoGms runs as part of the build)
./gradlew :app:assembleDebug

# 2. Build the offline data (downloads OSM extracts; takes a while, needs disk + RAM)
scripts/build-pmtiles.sh              # vector basemap  -> scripts/data/*.pmtiles
scripts/build-graph.sh                # routing graph   -> scripts/data/tennessee-gh
scripts/build-geocoder.sh             # search index    -> scripts/data/geocoder.db

# 3. Install + push the data onto a connected device
./gradlew :app:installDebug -PdataPush   # debuggable build (so run-as can write app storage)
scripts/build-pmtiles.sh  --install
scripts/build-graph.sh    --install
scripts/build-geocoder.sh --install
./gradlew :app:installDebug              # reinstall the fast, non-debuggable build
```

> The two‑step install ("data‑push dance") exists because `run-as` only writes app‑internal storage on a *debuggable* build, while the production build is non‑debuggable for routing speed. Internal data persists across a same‑signing‑key reinstall.

The **region is configurable at build time** — point the data scripts at a different OSM extract and bounding box to cover your own area.

## Project layout

```
app/                     Android app (Compose UI, routing, geocoder, camera data)
  └─ src/main/java/com/ghostroute/app/
       ui/map/           Map screen, drive view, route + camera layers
       routing/          Camera-aware routing engine (GraphHopper)
       navigation/       Turn-by-turn engine (snapping, maneuvers, voice cues)
       geocode/          Offline FTS geocoder
       data/             Room entities, Overpass client, camera repository
scripts/                 Offline-data pipeline (basemap, graph, geocoder) + graphtool prototype
docs/                    Overview + assets
```

## Roadmap

- [ ] User‑configurable region / on‑demand region downloads
- [ ] In‑app settings (avoidance aggressiveness, units, theme)
- [ ] Broader camera types folded into avoidance (currently ALPR‑only by default)
- [ ] Android Auto (blocked today: the sandboxed Play `CAR` validator on de‑Googled devices)

## Acknowledgements

GhostRoute stands on open data and open source:

- **[OpenStreetMap](https://www.openstreetmap.org)** contributors — basemap + camera data (ODbL)
- **[DeFlock](https://deflock.me)** — community ALPR mapping
- **[GraphHopper](https://www.graphhopper.com)**, **[MapLibre](https://maplibre.org)**, **[Planetiler](https://github.com/onthegomap/planetiler)**, **[PMTiles](https://github.com/protomaps/PMTiles)**
- US DOT **National Address Database** (public domain)

## License

[MIT](LICENSE). Camera/map data is © OpenStreetMap contributors, licensed under the [ODbL](https://opendatacommons.org/licenses/odbl/).

---

<div align="center">
<sub>Built to make the invisible visible — and avoidable.</sub>
</div>

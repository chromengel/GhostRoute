# GhostRoute — Overview

*Offline, privacy-first turn-by-turn navigation that routes you **around** ALPR / Flock
surveillance cameras.*

---

## What it is

GhostRoute is an Android navigation app (Kotlin + Jetpack Compose) built for people who
want to drive without feeding automated license-plate readers. It plans routes that avoid
known camera locations, works **fully offline**, and is designed to run on **GrapheneOS**
with **zero Google dependencies**.

## Core principles

- **No Google anything.** No Google Play Services, no Firebase, no analytics, no
  phone-home. A build check (`:app:verifyNoGms`) scans every dependency and **fails the
  build** if a banned Google package (`com.google.android.gms`, `com.google.firebase`,
  `com.google.android.play`, …) sneaks in.
- **Navigation never touches the network.** Routing, the map, and search all run from data
  on the device.
- **Platform location only.** Uses Android's `LocationManager` (GPS), never Google's
  `FusedLocationProvider`.
- **Minimal, coarse network use** — only when you tap *Refresh cameras*, and only a
  rough map-tile bounding box is sent to the public OpenStreetMap Overpass service. No
  identity, no precise position.

## What works today

| Feature | Status |
|---|---|
| **Multiple distinct route choices** | ✅ Shows a *Fastest* route, a *Fewest-cameras* route, and genuinely different alternate corridors (not near-duplicates). |
| **Camera-avoiding routing** | ✅ Penalizes routes for each camera passed; a slider/options bias toward avoidance. |
| **Offline map** | ✅ MapLibre vector basemap rendered from on-device tiles. |
| **Offline search** | ✅ Search by place name, by "City, State", and by full house-number street address. Results show address/type subtitles so same-named places (e.g. several "Kroger") can be told apart. |
| **Camera markers + tap to identify** | ✅ Red dot = a camera; triangle = a camera with a known facing direction. Tap any marker → card says e.g. "ALPR camera · Facing northeast". |
| **Map-following camera refresh** | ✅ Scroll the map anywhere and *Refresh cameras* downloads cameras for **that** area. |
| **Downloaded regions / travel use** | ✅ Currently ships **Tennessee + Western North Carolina** as one connected offline dataset (basemap, routing graph, and search index), so cross-border trips route end-to-end with no signal. |

## How it's built (high level)

- **UI:** Jetpack Compose + Material3, MapLibre Native for the map.
- **Routing:** GraphHopper (offline road graph) with a custom alternative-route pass that
  produces distinct corridors and a camera-avoidance cost.
- **Search:** a local SQLite full-text index built from OpenStreetMap data (places, POIs,
  streets, and individual address points).
- **Data pipeline:** OpenStreetMap extracts (`osmium`) → vector tiles (`planetiler`) →
  routing graph (GraphHopper) → search index (custom script). All offline-buildable.

---

## The one thing that doesn't work yet: Android Auto

**Goal:** show GhostRoute's map on the car's built-in display (e.g. a 2023 Mazda CX-50)
through Android Auto.

**What's done:** The Android Auto integration is fully built and Google-free — it uses
the AndroidX *Car App Library* (`androidx.car.app`), registers a navigation `CarAppService`,
draws the offline map onto the car screen via MapLibre's snapshotter, and passes the
`verifyNoGms` check. The phone's operating system **recognizes GhostRoute as a valid
Android Auto navigation app.**

**Why it still won't load — the blocker:** Android Auto refuses to launch the app. Its
internal validator tries to ask **Google Play Services** one question: *"Did the Play
Store install this app?"* On GrapheneOS, Google Play runs in a stripped-down sandbox, and
the specific service that answers that question (`isPackageInstalledByPlay`) **never
connects**. With no answer, Android Auto's validator denies the app by default.

Device logs confirm it:

```
CAR.VALIDATOR: isPackageInstalledByPlayCheck service not connected
CAR.VALIDATOR: Package DENIED; failed all other checks [com.ghostroute.app]
```

**Crucially, this is not a GhostRoute bug.** The exact same denial hits other open-source
navigation apps (e.g. OsmAnd) on the same setup — the wall is GrapheneOS's sandboxed
Google Play, not our code.

**Bypasses already tried and ruled out:**

- Android Auto "Unknown sources" developer toggle — enabled, still denied.
- Android Auto "Application Mode = Developer" — still denied.
- Spoofing the installer to look like the Play Store
  (`adb install -i com.android.vending`) — confirmed applied, still denied.
- Third-party tools (AAAD) — only install from their own catalog; their trick is the same
  installer spoof we already tried.

**Where it stands:** The app is complete and correct for Android Auto; the remaining
problem is a **GrapheneOS + sandboxed-Google-Play limitation** outside the app itself. The
open question is whether any GrapheneOS-side configuration (or community workaround) can
make the Play validation service respond — or whether this is genuinely a hard limit of
running Android Auto's unapproved-app path on GrapheneOS.

---

*Last updated: 2026-06-07*

#!/usr/bin/env bash
# GhostRoute Phase 1 — build the offline PMTiles basemap.
#
# Uses Planetiler (https://github.com/onthegomap/planetiler) to turn a Geofabrik
# OSM extract into a single OpenMapTiles-schema .pmtiles file that MapLibre
# renders 100% offline. Default area: Tennessee.
#
# Requirements: Java 17+ and ~1.5x the extract size in RAM (TN is comfortable on
# 8 GB). Output for Tennessee is roughly 200–400 MB.
#
# Usage:
#   scripts/build-pmtiles.sh                 # full Tennessee
#   scripts/build-pmtiles.sh --bounds "-84.20,35.50,-83.70,35.90"   # small bbox test (fast)
#
# After building, install onto a connected device/emulator:
#   scripts/build-pmtiles.sh --install
set -euo pipefail

cd "$(dirname "$0")/.."

AREA="tennessee"
OUT_DIR="scripts/data"
OUT_FILE="$OUT_DIR/tennessee.pmtiles"
PLANETILER_VERSION="0.10.2"
PLANETILER_JAR="$OUT_DIR/planetiler-${PLANETILER_VERSION}.jar"
PLANETILER_URL="https://github.com/onthegomap/planetiler/releases/download/v${PLANETILER_VERSION}/planetiler.jar"
APP_ID="com.ghostroute.app"
BASEMAP_REL="files/basemap/tennessee.pmtiles"

BOUNDS=""
DO_INSTALL=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --bounds) BOUNDS="$2"; shift 2 ;;
        --install) DO_INSTALL=1; shift ;;
        *) echo "Unknown arg: $1" >&2; exit 2 ;;
    esac
done

# Resolve a Java 17+ runtime: prefer JAVA_HOME, fall back to Android Studio's JBR.
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/java}"
if [[ -z "${JAVA_BIN:-}" || ! -x "$JAVA_BIN" ]]; then
    AS_JBR="/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java"
    [[ -x "$AS_JBR" ]] && JAVA_BIN="$AS_JBR" || JAVA_BIN="java"
fi

mkdir -p "$OUT_DIR"

if [[ "$DO_INSTALL" == "1" ]]; then
    if [[ ! -f "$OUT_FILE" ]]; then
        echo "No basemap at $OUT_FILE — build it first (run without --install)." >&2
        exit 1
    fi
    echo "==> Pushing $OUT_FILE to device app storage ($APP_ID)"
    adb push "$OUT_FILE" "/data/local/tmp/tennessee.pmtiles"
    adb shell run-as "$APP_ID" mkdir -p files/basemap
    adb shell "run-as $APP_ID sh -c 'cp /data/local/tmp/tennessee.pmtiles $BASEMAP_REL'"
    adb shell rm -f /data/local/tmp/tennessee.pmtiles
    echo "✓ Installed. Reopen GhostRoute — the offline map should render."
    exit 0
fi

if [[ ! -f "$PLANETILER_JAR" ]]; then
    echo "==> Downloading Planetiler $PLANETILER_VERSION"
    curl -L --fail -o "$PLANETILER_JAR" "$PLANETILER_URL"
fi

echo "==> Building PMTiles for area=$AREA ${BOUNDS:+(bounds $BOUNDS)}"
BOUNDS_ARG=()
[[ -n "$BOUNDS" ]] && BOUNDS_ARG=(--bounds="$BOUNDS")

"$JAVA_BIN" -Xmx6g -jar "$PLANETILER_JAR" \
    --download \
    --area="$AREA" \
    ${BOUNDS_ARG[@]+"${BOUNDS_ARG[@]}"} \
    --output="$OUT_FILE" \
    --force

echo
echo "✓ Built $OUT_FILE ($(du -h "$OUT_FILE" | cut -f1))"
echo "  Install on a device with: scripts/build-pmtiles.sh --install"

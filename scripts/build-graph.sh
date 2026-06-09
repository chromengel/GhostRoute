#!/usr/bin/env bash
# GhostRoute Phase 3 — build the offline GraphHopper routing graph.
#
# Imports the Tennessee OSM extract (already on disk from the PMTiles build,
# data/sources/tennessee.osm.pbf) into a GraphHopper graph the app loads
# on-device. Uses scripts/graphtool (graphhopper-core 7.0) so the on-disk graph
# format matches the app's graphhopper-core dependency exactly, and runs a
# desktop smoke-test route to validate the graph before any device round-trip.
#
# Usage:
#   scripts/build-graph.sh            # build the graph + smoke test
#   scripts/build-graph.sh --install  # push the built graph onto the device
set -euo pipefail

cd "$(dirname "$0")/.."

PBF="data/sources/tennessee.osm.pbf"
GRAPH_DIR="scripts/data/tennessee-gh"
APP_ID="com.ghostroute.app"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"

if [[ "${1:-}" == "--install" ]]; then
    if [[ ! -d "$GRAPH_DIR" ]]; then
        echo "No graph at $GRAPH_DIR — build it first (run without --install)." >&2
        exit 1
    fi
    echo "==> Pushing graph to device app storage ($APP_ID)"
    "$ADB" shell run-as "$APP_ID" mkdir -p files/graph
    "$ADB" shell rm -rf /data/local/tmp/tennessee-gh
    "$ADB" push "$GRAPH_DIR" /data/local/tmp/tennessee-gh
    "$ADB" shell "run-as $APP_ID sh -c 'rm -rf files/graph/tennessee-gh && cp -r /data/local/tmp/tennessee-gh files/graph/tennessee-gh'"
    "$ADB" shell rm -rf /data/local/tmp/tennessee-gh
    echo "✓ Graph installed. Reopen GhostRoute — routing is available."
    exit 0
fi

if [[ ! -f "$PBF" ]]; then
    echo "Missing $PBF — run scripts/build-pmtiles.sh first (it downloads the extract)." >&2
    exit 1
fi

# Use the repo's Gradle wrapper (JBR resolved by caller via JAVA_HOME).
echo "==> Building graph via scripts/graphtool (graphhopper-core 7.0)"
rm -rf "$GRAPH_DIR"
./gradlew -p scripts/graphtool --quiet run --args="$PWD/$PBF $PWD/$GRAPH_DIR"

echo
echo "✓ Built $GRAPH_DIR ($(du -sh "$GRAPH_DIR" | cut -f1))"
echo "  Install on a device with: scripts/build-graph.sh --install"

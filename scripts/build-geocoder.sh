#!/usr/bin/env bash
# Builds the offline geocoder index (SQLite + FTS4) from the OSM extract and,
# with --install, pushes it onto a connected device — like the routing graph,
# the index is too large for the APK and lives in app storage.
#
#   scripts/build-geocoder.sh            # build scripts/data/geocoder.db
#   scripts/build-geocoder.sh --install  # build (if needed) + push to the device
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TN_PBF="$ROOT/data/sources/tennessee.osm.pbf"
NC_PBF="$ROOT/data/sources/north-carolina.osm.pbf"
WNC_PBF="$ROOT/data/sources/wnc.osm.pbf"
WNC_BBOX="-84.35,34.95,-81.0,36.60"   # Western NC: Murphy/Cherokee -> Hickory/Morganton, SC line -> VA line
OUT="$ROOT/scripts/data/geocoder.db"
VENV="${GEOCODER_VENV:-/tmp/geocoder-venv}"
APP_ID="com.ghostroute.app"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"

if [[ "${1:-}" == "--install" ]]; then
    if [[ ! -f "$OUT" ]]; then echo "No $OUT — build it first (run without --install)." >&2; exit 1; fi
    # Copy into the app's INTERNAL files dir via run-as. IMPORTANT: run-as only works on a
    # DEBUGGABLE build, but the app ships non-debuggable (for routing speed) and adb can't
    # write app-internal storage otherwise (no adb root on a GrapheneOS user build; the
    # app+adb get isolated FUSE views of Android/data so external push isn't visible to the
    # app). So data updates use the dance: install a debuggable build, push, reinstall the
    # non-debuggable build (internal files persist across same-key reinstalls).
    echo "==> Pushing geocoder index to device (run-as; needs a debuggable build installed)"
    "$ADB" push "$OUT" /data/local/tmp/geocoder.db
    "$ADB" shell run-as "$APP_ID" mkdir -p files/geocoder
    "$ADB" shell "run-as $APP_ID sh -c 'cp /data/local/tmp/geocoder.db files/geocoder/geocoder.db'"
    "$ADB" shell rm /data/local/tmp/geocoder.db
    echo "==> Installed. Restart the app to pick it up."
    exit 0
fi

if [[ ! -f "$TN_PBF" ]]; then
    echo "Missing $TN_PBF — run scripts/build-pmtiles.sh first (it downloads the extract)." >&2
    exit 1
fi

# Western NC clip (state-tagged NC). Recreate from the NC extract if missing.
if [[ ! -f "$WNC_PBF" ]]; then
    if [[ ! -f "$NC_PBF" ]]; then
        echo "Missing $WNC_PBF and $NC_PBF — download the NC extract first." >&2
        exit 1
    fi
    echo "==> Clipping Western NC from $NC_PBF ($WNC_BBOX)"
    osmium extract --bbox="$WNC_BBOX" "$NC_PBF" -o "$WNC_PBF" --overwrite
fi

# pyosmium is a native build; keep it in a throwaway venv (Homebrew python blocks global pip).
if [[ ! -x "$VENV/bin/python" ]]; then
    echo "==> Creating venv + installing pyosmium at $VENV"
    python3 -m venv "$VENV"
    "$VENV/bin/pip" install --quiet osmium
fi

echo "==> Building geocoder index (TN + Western NC)"
mkdir -p "$ROOT/scripts/data"
"$VENV/bin/python" "$ROOT/scripts/build-geocoder.py" "$OUT" "$TN_PBF:TN" "$WNC_PBF:NC"

# Optional: merge the US DOT National Address Database (NAD) house-number points for
# TN + Western NC, replacing the sparse OSM addresses with comprehensive coverage. Only
# runs if the NAD national zip has been downloaded to data/sources/ (it's ~8.5 GB; see
# the download URL in the NAD note in memory). Skipped gracefully if absent.
NAD_ZIP="$ROOT/data/sources/nad_r22.zip"
NAD_CSV="$ROOT/scripts/data/nad-tn-wnc.csv"
if [[ -f "$NAD_CSV" ]]; then
    echo "==> Reusing existing filtered NAD CSV ($NAD_CSV); delete it to re-filter from the zip"
    "$VENV/bin/python" "$ROOT/scripts/add-nad-addresses.py" "$OUT" "$NAD_CSV"
elif [[ -f "$NAD_ZIP" ]]; then
    echo "==> Filtering NAD addresses (TN + Western NC) from $NAD_ZIP"
    "$VENV/bin/python" "$ROOT/scripts/filter-nad.py" "$NAD_ZIP" "$NAD_CSV"
    echo "==> Merging NAD house-number addresses into the index"
    "$VENV/bin/python" "$ROOT/scripts/add-nad-addresses.py" "$OUT" "$NAD_CSV"
else
    echo "==> (No $NAD_ZIP — skipping house-number address dataset; OSM streets/addresses only)"
fi
echo "==> Done: $OUT"

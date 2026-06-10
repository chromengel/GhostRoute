#!/usr/bin/env bash
# GhostRoute — build the offline TOPOGRAPHIC overlay (hillshade relief + contour lines).
#
# Turns a Digital Elevation Model (DEM GeoTIFF) into two PMTiles the app renders 100%
# offline as a toggleable overlay:
#   hillshade.pmtiles  — shaded relief raster (MapLibre raster layer, low opacity)
#   contours.pmtiles   — contour vector tiles (layer "contour", attribute "ele")
#
# Tools (install once):  brew install gdal tippecanoe pmtiles
#
# Get a DEM for your region (TN + Western NC), e.g. USGS 3DEP 1/3 arc-second or SRTM 30 m,
# mosaicked to a single GeoTIFF, then:
#   TOPO_DEM=/path/to/region-dem.tif  scripts/build-topo.sh
#   scripts/build-topo.sh --install            # push both PMTiles onto the device
#
# Tip: validate the whole chain on a SMALL DEM (one county) first — it's fast — before the
# full TN+WNC run.
set -euo pipefail

cd "$(dirname "$0")/.."

OUT_DIR="scripts/data"
HILLSHADE="$OUT_DIR/hillshade.pmtiles"
CONTOURS="$OUT_DIR/contours.pmtiles"
APP_ID="com.ghostroute.app"
ADB="${ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}"

# Tuning
CONTOUR_INTERVAL="${CONTOUR_INTERVAL:-40}"   # metres between contour lines (index every 200)
HS_MIN_Z="${HS_MIN_Z:-8}"
HS_MAX_Z="${HS_MAX_Z:-13}"
CT_MIN_Z="${CT_MIN_Z:-9}"
CT_MAX_Z="${CT_MAX_Z:-14}"

if [[ "${1:-}" == "--install" ]]; then
    [[ -f "$HILLSHADE" || -f "$CONTOURS" ]] || { echo "Nothing built yet — run without --install." >&2; exit 1; }
    echo "==> Pushing topo overlay into app storage ($APP_ID); needs a DEBUGGABLE build installed"
    "$ADB" shell run-as "$APP_ID" mkdir -p files/topo
    for f in "$HILLSHADE" "$CONTOURS"; do
        [[ -f "$f" ]] || continue
        name="$(basename "$f")"
        "$ADB" push "$f" "/data/local/tmp/$name"
        "$ADB" shell "run-as $APP_ID sh -c 'cp /data/local/tmp/$name files/topo/$name'"
        "$ADB" shell rm -f "/data/local/tmp/$name"
        echo "  ✓ $name"
    done
    echo "✓ Installed. Reopen GhostRoute — the ⋮ menu now has a Topographic toggle."
    exit 0
fi

for t in gdaldem gdal_contour gdalwarp gdal_translate gdaladdo ogr2ogr tippecanoe pmtiles; do
    command -v "$t" >/dev/null || { echo "Missing '$t'. Install: brew install gdal tippecanoe pmtiles" >&2; exit 1; }
done

DEM="${TOPO_DEM:-}"
[[ -n "$DEM" && -f "$DEM" ]] || { echo "Set TOPO_DEM to your region DEM GeoTIFF (see header)." >&2; exit 1; }

mkdir -p "$OUT_DIR"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "==> 1/4 Hillshade relief from DEM"
gdaldem hillshade -compute_edges -z 1.4 -az 315 -alt 45 "$DEM" "$TMP/hs.tif"
gdalwarp -t_srs EPSG:3857 -r bilinear "$TMP/hs.tif" "$TMP/hs3857.tif"

echo "==> 2/4 Hillshade -> raster PMTiles (z$HS_MIN_Z-$HS_MAX_Z)"
gdal_translate -of MBTILES -co TILE_FORMAT=PNG "$TMP/hs3857.tif" "$TMP/hs.mbtiles"
gdaladdo -r average "$TMP/hs.mbtiles" $(awk "BEGIN{for(i=1;i<=($HS_MAX_Z-$HS_MIN_Z);i++)printf 2^i\" \"}")
pmtiles convert "$TMP/hs.mbtiles" "$HILLSHADE"

echo "==> 3/4 Contour lines (every ${CONTOUR_INTERVAL} m)"
gdal_contour -a ele -i "$CONTOUR_INTERVAL" "$DEM" "$TMP/contours.gpkg"
ogr2ogr -f GeoJSONSeq "$TMP/contours.geojsonl" "$TMP/contours.gpkg"

echo "==> 4/4 Contours -> vector PMTiles (layer 'contour', z$CT_MIN_Z-$CT_MAX_Z)"
tippecanoe -o "$CONTOURS" -f -l contour -Z"$CT_MIN_Z" -z"$CT_MAX_Z" \
    --drop-densest-as-needed --simplification=4 "$TMP/contours.geojsonl"

echo
echo "✓ Built:"
echo "    $HILLSHADE ($(du -h "$HILLSHADE" | cut -f1))"
echo "    $CONTOURS ($(du -h "$CONTOURS" | cut -f1))"
echo "  Install on a device with: scripts/build-topo.sh --install"

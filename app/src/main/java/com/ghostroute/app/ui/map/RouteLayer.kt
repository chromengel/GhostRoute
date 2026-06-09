package com.ghostroute.app.ui.map

import com.ghostroute.app.routing.RoutePoint
import kotlin.math.cos
import kotlin.math.sqrt
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val ROUTE_SOURCE = "ghostroute-route-source"
private const val ROUTE_CASING_LAYER = "ghostroute-route-casing"
private const val ROUTE_LINE_LAYER = "ghostroute-route-line"
private const val TRAVELED_SOURCE = "ghostroute-traveled-source"
private const val TRAVELED_LINE_LAYER = "ghostroute-traveled-line"
private const val ALT_SOURCE = "ghostroute-alt-source"
private const val ALT_LINE_LAYER = "ghostroute-alt-line"
private const val DEST_SOURCE = "ghostroute-destination-source"
private const val DEST_LAYER = "ghostroute-destination-dot"

/**
 * Adds the route layers (faint alternatives underneath, the selected route with a
 * darker casing on top) and the destination marker. Idempotent.
 */
fun Style.addRouteLayers() {
    if (getSource(ROUTE_SOURCE) != null) return
    addSource(GeoJsonSource(ALT_SOURCE))
    addSource(GeoJsonSource(ROUTE_SOURCE))
    addSource(GeoJsonSource(TRAVELED_SOURCE))
    addSource(GeoJsonSource(DEST_SOURCE))

    // Unselected alternatives — faint, drawn beneath the chosen route.
    addLayer(
        LineLayer(ALT_LINE_LAYER, ALT_SOURCE).withProperties(
            PropertyFactory.lineColor("#6B7785"),
            PropertyFactory.lineWidth(4f),
            PropertyFactory.lineOpacity(0.55f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )

    addLayer(
        LineLayer(ROUTE_CASING_LAYER, ROUTE_SOURCE).withProperties(
            PropertyFactory.lineColor("#0B3D5C"),
            PropertyFactory.lineWidth(9f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
    addLayer(
        LineLayer(ROUTE_LINE_LAYER, ROUTE_SOURCE).withProperties(
            PropertyFactory.lineColor("#4F9DFF"),
            PropertyFactory.lineWidth(5.5f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
    // The already-driven portion: a faint, see-through pale-blue trail. During navigation
    // the bright line + casing only cover the road AHEAD (ROUTE_SOURCE = remaining), so this
    // sits over the bare map and its low opacity lets the map show through — the completed
    // stretch reads as a ghost trail. Only populated while navigating (see [updateTraveled]).
    addLayer(
        LineLayer(TRAVELED_LINE_LAYER, TRAVELED_SOURCE).withProperties(
            PropertyFactory.lineColor("#CFE6FB"),
            PropertyFactory.lineWidth(5f),
            PropertyFactory.lineOpacity(0.38f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
    addLayer(
        CircleLayer(DEST_LAYER, DEST_SOURCE).withProperties(
            PropertyFactory.circleRadius(8f),
            PropertyFactory.circleColor("#4F9DFF"),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(2.5f),
        ),
    )
}

/** Draws the selected route prominently and the other alternatives faintly. */
fun Style.updateRoutes(routes: List<List<RoutePoint>>, selectedIndex: Int) {
    val routeSrc = getSourceAs<GeoJsonSource>(ROUTE_SOURCE) ?: return
    val altSrc = getSourceAs<GeoJsonSource>(ALT_SOURCE) ?: return

    val selected = routes.getOrNull(selectedIndex) ?: routes.firstOrNull()
    routeSrc.setGeoJson(lineOrEmpty(selected))

    val altFeatures = routes
        .filterIndexed { i, pts -> i != selectedIndex && pts.size >= 2 }
        .map { it.toLineFeature() }
    altSrc.setGeoJson(FeatureCollection.fromFeatures(altFeatures.toTypedArray()))

    // A new/changed route resets driven progress; clear any leftover traveled overlay so it
    // doesn't briefly straddle the old geometry until the next location tick repaints it.
    getSourceAs<GeoJsonSource>(TRAVELED_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
}

/**
 * Splits the selected route at the snapped progress point so the vivid line + casing cover
 * only the road AHEAD, while the first [traveledM] meters become a faint, see-through trail.
 *
 * Pass `traveledM <= 0` to show the whole route ahead (route preview / not navigating); pass
 * `points == null` to leave the route source alone (no route to show). Called once per fix.
 */
fun Style.updateTraveled(points: List<RoutePoint>?, traveledM: Double) {
    val travSrc = getSourceAs<GeoJsonSource>(TRAVELED_SOURCE) ?: return
    val routeSrc = getSourceAs<GeoJsonSource>(ROUTE_SOURCE) ?: return
    if (points == null || points.size < 2) {
        travSrc.setLine(emptyList())
        return
    }
    if (traveledM <= 0.0) {
        travSrc.setLine(emptyList())
        routeSrc.setLine(points)            // whole route still ahead
        return
    }
    travSrc.setLine(sliceUpTo(points, traveledM))   // behind us → faint trail
    routeSrc.setLine(sliceFrom(points, traveledM))  // ahead of us → vivid line + casing
}

/** Sets a line source from [points], or clears it if there aren't enough for a segment. */
private fun GeoJsonSource.setLine(points: List<RoutePoint>) {
    if (points.size < 2) {
        setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
    } else {
        setGeoJson(points.toLineFeature())
    }
}

/** The leading sub-polyline covering the first [meters] of [points], with the final vertex
 *  interpolated so the cut lands exactly at the snapped position (not the nearest vertex). */
private fun sliceUpTo(points: List<RoutePoint>, meters: Double): List<RoutePoint> {
    val out = ArrayList<RoutePoint>(points.size)
    out.add(points[0])
    var acc = 0.0
    for (i in 1 until points.size) {
        val seg = metersBetween(points[i - 1], points[i])
        if (acc + seg >= meters) {
            val t = if (seg == 0.0) 0.0 else (meters - acc) / seg
            out.add(
                RoutePoint(
                    lat = points[i - 1].lat + (points[i].lat - points[i - 1].lat) * t,
                    lon = points[i - 1].lon + (points[i].lon - points[i - 1].lon) * t,
                ),
            )
            return out
        }
        acc += seg
        out.add(points[i])
    }
    return out
}

/** The trailing sub-polyline from [meters] along [points] to the end, with the first vertex
 *  interpolated so it starts exactly at the snapped position (meeting the traveled prefix). */
private fun sliceFrom(points: List<RoutePoint>, meters: Double): List<RoutePoint> {
    var acc = 0.0
    for (i in 1 until points.size) {
        val seg = metersBetween(points[i - 1], points[i])
        if (acc + seg >= meters) {
            val t = if (seg == 0.0) 0.0 else (meters - acc) / seg
            val cut = RoutePoint(
                lat = points[i - 1].lat + (points[i].lat - points[i - 1].lat) * t,
                lon = points[i - 1].lon + (points[i].lon - points[i - 1].lon) * t,
            )
            val out = ArrayList<RoutePoint>(points.size - i + 1)
            out.add(cut)
            for (j in i until points.size) out.add(points[j])
            return out
        }
        acc += seg
    }
    return listOf(points.last()) // progressed past the end → nothing ahead
}

private const val M_PER_DEG_LAT = 111_320.0

/** Equirectangular meters between two close points — matches NavigationEngine's snap math. */
private fun metersBetween(a: RoutePoint, b: RoutePoint): Double {
    val mPerDegLon = M_PER_DEG_LAT * cos(Math.toRadians(a.lat))
    val dx = (b.lon - a.lon) * mPerDegLon
    val dy = (b.lat - a.lat) * M_PER_DEG_LAT
    return sqrt(dx * dx + dy * dy)
}

private fun lineOrEmpty(points: List<RoutePoint>?): FeatureCollection =
    if (points == null || points.size < 2) {
        FeatureCollection.fromFeatures(emptyArray())
    } else {
        FeatureCollection.fromFeatures(arrayOf(points.toLineFeature()))
    }

private fun List<RoutePoint>.toLineFeature(): Feature =
    Feature.fromGeometry(LineString.fromLngLats(map { Point.fromLngLat(it.lon, it.lat) }))

/** Places (or clears) the destination marker. */
fun Style.updateDestination(point: RoutePoint?) {
    val source = getSourceAs<GeoJsonSource>(DEST_SOURCE) ?: return
    if (point == null) {
        source.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
    } else {
        source.setGeoJson(Feature.fromGeometry(Point.fromLngLat(point.lon, point.lat)))
    }
}

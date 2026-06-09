package com.ghostroute.app.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import com.ghostroute.app.data.CameraEntity
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

private const val CAMERA_SOURCE = "ghostroute-cameras-source"
private const val CAMERA_DOT_LAYER = "ghostroute-camera-dots"
private const val CAMERA_ARROW_LAYER = "ghostroute-camera-arrows"
private const val CAMERA_ARROW_IMAGE = "ghostroute-camera-arrow"

private const val PROP_DIRECTION = "direction"
private const val PROP_TYPE = "type"
private const val PROP_TIMESTAMP = "osm_ts"

/**
 * Registers the camera source, the red dot layer (every camera), and the
 * directional arrow layer (only cameras with a known read bearing). Idempotent.
 */
fun Style.addCameraLayers() {
    if (getSource(CAMERA_SOURCE) != null) return

    addImage(CAMERA_ARROW_IMAGE, createCameraArrowBitmap())
    addSource(GeoJsonSource(CAMERA_SOURCE))

    addLayer(
        CircleLayer(CAMERA_DOT_LAYER, CAMERA_SOURCE).withProperties(
            PropertyFactory.circleRadius(6f),
            // Color by type so the plate-reading threat (ALPR/Flock = bright red) stands out
            // from the rest: gunshot = amber, speed = blue, CCTV/dome = gray, untyped = muted.
            PropertyFactory.circleColor(
                Expression.match(
                    Expression.get(PROP_TYPE),
                    Expression.literal(CameraEntity.TYPE_ALPR), Expression.color(Color.parseColor("#FF5252")),
                    Expression.literal(CameraEntity.TYPE_GUNSHOT), Expression.color(Color.parseColor("#FFB300")),
                    Expression.literal(CameraEntity.TYPE_SPEED), Expression.color(Color.parseColor("#4C8DFF")),
                    Expression.literal(CameraEntity.TYPE_CAMERA), Expression.color(Color.parseColor("#9E9E9E")),
                    Expression.literal(CameraEntity.TYPE_DOME), Expression.color(Color.parseColor("#9E9E9E")),
                    Expression.color(Color.parseColor("#BDBDBD")), // other / low-confidence
                ),
            ),
            PropertyFactory.circleStrokeColor("#2A0A0A"),
            PropertyFactory.circleStrokeWidth(1.5f),
            PropertyFactory.circleOpacity(0.95f),
        ),
    )

    // Arrow points in the camera's read direction (icon drawn pointing north,
    // then rotated by the per-feature `direction` bearing, aligned to the map).
    val arrowLayer = SymbolLayer(CAMERA_ARROW_LAYER, CAMERA_SOURCE).withProperties(
        PropertyFactory.iconImage(CAMERA_ARROW_IMAGE),
        PropertyFactory.iconRotate(Expression.get(PROP_DIRECTION)),
        PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
        PropertyFactory.iconAllowOverlap(true),
        PropertyFactory.iconIgnorePlacement(true),
        PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
        PropertyFactory.iconSize(1.0f),
    )
    arrowLayer.setFilter(Expression.has(PROP_DIRECTION))
    addLayer(arrowLayer)
}

/** Pushes the current camera set into the map source as GeoJSON features. */
fun Style.updateCameras(cameras: List<CameraEntity>) {
    val source = getSourceAs<GeoJsonSource>(CAMERA_SOURCE) ?: return
    val features = cameras.map { camera ->
        Feature.fromGeometry(Point.fromLngLat(camera.lon, camera.lat)).apply {
            addStringProperty(PROP_TYPE, camera.type)
            camera.direction?.let { addNumberProperty(PROP_DIRECTION, it) }
            camera.osmTimestamp?.let { addNumberProperty(PROP_TIMESTAMP, it) }
        }
    }
    source.setGeoJson(FeatureCollection.fromFeatures(features))
}

fun Style.setCamerasVisible(visible: Boolean) {
    val value = if (visible) Property.VISIBLE else Property.NONE
    getLayer(CAMERA_DOT_LAYER)?.setProperties(PropertyFactory.visibility(value))
    getLayer(CAMERA_ARROW_LAYER)?.setProperties(PropertyFactory.visibility(value))
}

/** What a tapped camera is: its [type], the bearing it reads toward (if known), and the
 *  OSM last-edit time in epoch millis (if known) — a data-freshness hint. */
data class CameraInfo(val type: String, val directionDeg: Double?, val osmTimestamp: Long?)

private const val TAP_PAD_PX = 26f

/**
 * Returns the camera marker under [point] (a screen coordinate), or null if the tap
 * missed. Queries a small box around the point so the small dots are easy to hit.
 */
fun MapLibreMap.cameraAt(point: PointF): CameraInfo? {
    val box = RectF(point.x - TAP_PAD_PX, point.y - TAP_PAD_PX, point.x + TAP_PAD_PX, point.y + TAP_PAD_PX)
    val feature = queryRenderedFeatures(box, CAMERA_DOT_LAYER, CAMERA_ARROW_LAYER).firstOrNull() ?: return null
    val type = feature.getStringProperty(PROP_TYPE) ?: CameraEntity.TYPE_ALPR
    val dir = if (feature.hasProperty(PROP_DIRECTION)) feature.getNumberProperty(PROP_DIRECTION)?.toDouble() else null
    val ts = if (feature.hasProperty(PROP_TIMESTAMP)) feature.getNumberProperty(PROP_TIMESTAMP)?.toLong() else null
    return CameraInfo(type, dir, ts)
}

/** A small red arrowhead pointing up (north); MapLibre rotates it per-feature. */
private fun createCameraArrowBitmap(): Bitmap {
    val size = 48
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val s = size.toFloat()
    val path = Path().apply {
        moveTo(s / 2f, s * 0.06f)   // tip (north)
        lineTo(s * 0.80f, s * 0.60f) // right base
        lineTo(s / 2f, s * 0.44f)    // center notch
        lineTo(s * 0.20f, s * 0.60f) // left base
        close()
    }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5252")
        style = Paint.Style.FILL
    }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    canvas.drawPath(path, fill)
    canvas.drawPath(path, stroke)
    return bitmap
}

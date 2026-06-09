package com.ghostroute.app.car

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.location.Location
import android.util.Log
import android.view.Surface
import com.ghostroute.app.data.CameraEntity
import com.ghostroute.app.map.BasemapProvider
import com.ghostroute.app.map.SunCalculator
import com.ghostroute.app.routing.RoutePoint
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter

/**
 * Renders GhostRoute's offline map onto the Android Auto car surface.
 *
 * Android Auto navigation apps are handed a raw [Surface]; MapLibre's normal MapView
 * can't bind to it, so we use the offline [MapSnapshotter] (same renderer, no network)
 * to draw the basemap + the active route + cameras to a Bitmap, then blit that onto the
 * car surface and stamp the location puck at center. It re-renders as the car moves —
 * not 60 fps, but a reliable, fully-offline moving map on the head unit.
 */
class CarMapRenderer(private val context: Context) {

    private companion object {
        const val TAG = "GhostRouteCar"
        const val ZOOM = 15.5
    }

    private var surface: Surface? = null
    private var width = 0
    private var height = 0

    private var snapshotter: MapSnapshotter? = null
    private var styleJson: String? = null
    private var styleDirty = true
    private var night = false

    private var location: Location? = null
    private var route: List<RoutePoint> = emptyList()
    private var cameras: List<CameraEntity> = emptyList()

    private var rendering = false
    private var pending = false

    private val puckFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3DDC97"); style = Paint.Style.FILL
    }
    private val puckStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0E1116"); style = Paint.Style.STROKE; strokeWidth = 3f
    }

    fun setSurface(surface: Surface, width: Int, height: Int) {
        this.surface = surface
        this.width = width
        this.height = height
        // Size changed → the snapshotter must be rebuilt.
        snapshotter = null
        render()
    }

    fun clearSurface() {
        surface = null
        snapshotter?.cancel()
        snapshotter = null
    }

    fun updateLocation(location: Location) {
        this.location = location
        val isNight = SunCalculator.isNight(location.latitude, location.longitude, System.currentTimeMillis())
        if (isNight != night) {
            night = isNight
            styleDirty = true
        }
        render()
    }

    /** The route + camera overlay changed (route picked on the phone, or a camera sync). */
    fun setOverlay(route: List<RoutePoint>, cameras: List<CameraEntity>) {
        this.route = route
        this.cameras = cameras
        styleDirty = true
        render()
    }

    fun render() {
        val surface = surface ?: return
        if (width <= 0 || height <= 0 || !surface.isValid) return
        val loc = location ?: return
        if (rendering) { pending = true; return }
        rendering = true

        val snap = snapshotter ?: MapSnapshotter(
            context,
            MapSnapshotter.Options(width, height).withPixelRatio(1f).withLogo(false),
        ).also { snapshotter = it; styleDirty = true }

        if (styleDirty || styleJson == null) {
            styleJson = buildStyle()
            snap.setStyleJson(styleJson!!)
            styleDirty = false
        }
        snap.setSize(width, height)
        snap.setCameraPosition(
            CameraPosition.Builder()
                .target(LatLng(loc.latitude, loc.longitude))
                .zoom(ZOOM)
                .bearing(if (loc.hasBearing()) loc.bearing.toDouble() else 0.0)
                .build(),
        )
        snap.start(
            object : MapSnapshotter.SnapshotReadyCallback {
                override fun onSnapshotReady(snapshot: MapSnapshot) {
                    drawToSurface(snapshot.bitmap)
                    rendering = false
                    if (pending) { pending = false; render() }
                }
            },
            object : MapSnapshotter.ErrorHandler {
                override fun onError(error: String) {
                    Log.w(TAG, "snapshot error: $error")
                    rendering = false
                }
            },
        )
    }

    private fun drawToSurface(bitmap: Bitmap) {
        val surface = surface ?: return
        if (!surface.isValid) return
        val canvas = try { surface.lockCanvas(null) } catch (e: Exception) { return } ?: return
        try {
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            // Map is centered on the user, so the puck sits at the surface center.
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawCircle(cx, cy, 11f, puckFill)
            canvas.drawCircle(cx, cy, 11f, puckStroke)
        } finally {
            runCatching { surface.unlockCanvasAndPost(canvas) }
        }
    }

    /** Base offline style with the route line + camera dots injected as GeoJSON layers. */
    private fun buildStyle(): String {
        val root = JSONObject(BasemapProvider.buildStyleJson(context, night))
        val sources = root.getJSONObject("sources")
        val layers = root.getJSONArray("layers")

        if (route.size >= 2) {
            val coords = JSONArray()
            for (p in route) coords.put(JSONArray().put(p.lon).put(p.lat))
            val feature = JSONObject()
                .put("type", "Feature")
                .put("properties", JSONObject())
                .put("geometry", JSONObject().put("type", "LineString").put("coordinates", coords))
            sources.put("gr-car-route", JSONObject().put("type", "geojson").put("data", feature))
            layers.put(
                JSONObject().put("id", "gr-car-route-line").put("type", "line").put("source", "gr-car-route")
                    .put("layout", JSONObject().put("line-join", "round").put("line-cap", "round"))
                    .put("paint", JSONObject().put("line-color", "#4C8DFF").put("line-width", 6.0)),
            )
        }

        if (cameras.isNotEmpty()) {
            val feats = JSONArray()
            for (c in cameras) {
                feats.put(
                    JSONObject().put("type", "Feature").put("properties", JSONObject())
                        .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(c.lon).put(c.lat))),
                )
            }
            sources.put(
                "gr-car-cams",
                JSONObject().put("type", "geojson").put("data", JSONObject().put("type", "FeatureCollection").put("features", feats)),
            )
            layers.put(
                JSONObject().put("id", "gr-car-cams-dots").put("type", "circle").put("source", "gr-car-cams")
                    .put("paint", JSONObject().put("circle-radius", 5.0).put("circle-color", "#FF5252")
                        .put("circle-stroke-color", "#2A0A0A").put("circle-stroke-width", 1.0)),
            )
        }
        return root.toString()
    }
}

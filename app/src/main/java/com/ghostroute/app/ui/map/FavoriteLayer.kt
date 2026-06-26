package com.ghostroute.app.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.ghostroute.app.places.SavedPlace
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

private const val FAVORITE_SOURCE = "ghostroute-favorites-source"
private const val FAVORITE_LAYER = "ghostroute-favorites"
private const val FAVORITE_IMAGE = "ghostroute-favorite-pin"
private const val PROP_NAME = "name"

/**
 * Drops a labeled violet pin for each saved place (Home, Work, and pinned favorites) so the
 * user can see all their favorites at a glance. Pins sit above the cameras but below the
 * location puck. Idempotent.
 */
fun Style.addFavoriteLayers() {
    if (getSource(FAVORITE_SOURCE) != null) return
    addImage(FAVORITE_IMAGE, createFavoritePinBitmap())
    addSource(GeoJsonSource(FAVORITE_SOURCE))
    addLayer(
        SymbolLayer(FAVORITE_LAYER, FAVORITE_SOURCE).withProperties(
            PropertyFactory.iconImage(FAVORITE_IMAGE),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconSize(0.85f),
            PropertyFactory.textField(Expression.get(PROP_NAME)),
            PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
            PropertyFactory.textSize(11f),
            PropertyFactory.textColor("#3A2A66"),
            PropertyFactory.textHaloColor("#FFFFFF"),
            PropertyFactory.textHaloWidth(1.4f),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textOffset(arrayOf(0f, 0.5f)),
            PropertyFactory.textOptional(true),
            PropertyFactory.textAllowOverlap(false),
        ),
    )
}

/** Pushes the current favorites (display name + location) into the pin source. */
fun Style.updateFavorites(places: List<SavedPlace>) {
    val source = getSourceAs<GeoJsonSource>(FAVORITE_SOURCE) ?: return
    val features = places
        .filter { it.lat != 0.0 || it.lon != 0.0 }
        .map { place ->
            Feature.fromGeometry(Point.fromLngLat(place.lon, place.lat)).apply {
                addStringProperty(PROP_NAME, place.name)
            }
        }
    source.setGeoJson(FeatureCollection.fromFeatures(features))
}

fun Style.setFavoritesVisible(visible: Boolean) {
    val value = if (visible) Property.VISIBLE else Property.NONE
    getLayer(FAVORITE_LAYER)?.setProperties(PropertyFactory.visibility(value))
}

/** A violet map pin (teardrop head + point) with a white border and center dot. */
private fun createFavoritePinBitmap(): Bitmap {
    val w = 64
    val h = 84
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = w / 2f
    val border = 3f
    val r = w / 2f - border          // head radius
    val cy = r + border              // head center y

    fun pinPath(radius: Float, headY: Float, tip: Float): Path = Path().apply {
        // Round head + tapering point to (cx, tip).
        addCircle(cx, headY, radius, Path.Direction.CW)
        moveTo(cx - radius * 0.58f, headY + radius * 0.62f)
        lineTo(cx, tip)
        lineTo(cx + radius * 0.58f, headY + radius * 0.62f)
        close()
    }

    val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    val violet = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#7E57C2"); style = Paint.Style.FILL }

    // White outline (slightly larger), then the violet body, then a white center dot.
    canvas.drawPath(pinPath(r, cy, h.toFloat()), white)
    canvas.drawPath(pinPath(r - border, cy, h - border * 1.6f), violet)
    canvas.drawCircle(cx, cy, r * 0.34f, white)
    return bitmap
}

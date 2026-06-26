package com.ghostroute.app.ui.map

import android.content.Context
import com.ghostroute.app.map.TopoProvider
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.VectorSource

private const val HILLSHADE_SOURCE = "ghostroute-hillshade-src"
private const val HILLSHADE_LAYER = "ghostroute-hillshade"
private const val CONTOUR_SOURCE = "ghostroute-contour-src"
private const val CONTOUR_LINE_LAYER = "ghostroute-contour-line"
private const val CONTOUR_INDEX_LAYER = "ghostroute-contour-index"
private const val CONTOUR_LABEL_LAYER = "ghostroute-contour-label"

// The vector tile layer name + elevation attribute that scripts/build-topo.sh produces.
private const val CONTOUR_SOURCE_LAYER = "contour"
private const val ELE = "ele"
private const val INDEX_INTERVAL = 200   // bold "index" line + label every 200 units

// Insert topo beneath the roads so the route, roads, and labels stay legible on top.
private const val BELOW_LAYER = "road-secondary-casing"

private val TOPO_LAYERS = listOf(HILLSHADE_LAYER, CONTOUR_LINE_LAYER, CONTOUR_INDEX_LAYER, CONTOUR_LABEL_LAYER)

/**
 * Adds the topographic overlay (hillshade relief + contour lines) if its PMTiles are
 * installed. Layers start hidden — [setTopoVisible] flips them. Idempotent.
 */
fun Style.addTopoLayers(context: Context) {
    if (!TopoProvider.isInstalled(context)) return
    if (getSource(HILLSHADE_SOURCE) != null || getSource(CONTOUR_SOURCE) != null) return

    if (TopoProvider.hasHillshade(context)) {
        addSource(RasterSource(HILLSHADE_SOURCE, TopoProvider.hillshadeUrl(context), 256))
        addBelowRoads(
            RasterLayer(HILLSHADE_LAYER, HILLSHADE_SOURCE).withProperties(
                PropertyFactory.rasterOpacity(0.35f),
                PropertyFactory.visibility(Property.NONE),
            ),
        )
    }

    if (TopoProvider.hasContours(context)) {
        addSource(VectorSource(CONTOUR_SOURCE, TopoProvider.contoursUrl(context)))
        val isIndex = Expression.eq(
            Expression.mod(Expression.toNumber(Expression.get(ELE)), Expression.literal(INDEX_INTERVAL)),
            Expression.literal(0),
        )

        addBelowRoads(
            LineLayer(CONTOUR_LINE_LAYER, CONTOUR_SOURCE).apply {
                sourceLayer = CONTOUR_SOURCE_LAYER
                withProperties(
                    PropertyFactory.lineColor("#8A6D3B"),
                    PropertyFactory.lineWidth(0.6f),
                    PropertyFactory.lineOpacity(0.5f),
                    PropertyFactory.visibility(Property.NONE),
                )
            },
        )
        addBelowRoads(
            LineLayer(CONTOUR_INDEX_LAYER, CONTOUR_SOURCE).apply {
                sourceLayer = CONTOUR_SOURCE_LAYER
                setFilter(isIndex)
                withProperties(
                    PropertyFactory.lineColor("#7A5A2C"),
                    PropertyFactory.lineWidth(1.1f),
                    PropertyFactory.lineOpacity(0.75f),
                    PropertyFactory.visibility(Property.NONE),
                )
            },
        )
        addBelowRoads(
            SymbolLayer(CONTOUR_LABEL_LAYER, CONTOUR_SOURCE).apply {
                sourceLayer = CONTOUR_SOURCE_LAYER
                setFilter(isIndex)
                withProperties(
                    PropertyFactory.symbolPlacement(Property.SYMBOL_PLACEMENT_LINE),
                    PropertyFactory.textField(Expression.get(ELE)),
                    PropertyFactory.textSize(10f),
                    PropertyFactory.textColor("#6B4F25"),
                    PropertyFactory.textHaloColor("#FFFFFF"),
                    PropertyFactory.textHaloWidth(1.0f),
                    PropertyFactory.symbolSpacing(280f),
                    PropertyFactory.visibility(Property.NONE),
                )
            },
        )
    }
}

private fun Style.addBelowRoads(layer: Layer) {
    if (getLayer(BELOW_LAYER) != null) addLayerBelow(layer, BELOW_LAYER) else addLayer(layer)
}

/** Shows or hides the whole topographic overlay. No-op if it isn't installed. */
fun Style.setTopoVisible(visible: Boolean) {
    val value = if (visible) Property.VISIBLE else Property.NONE
    TOPO_LAYERS.forEach { getLayer(it)?.setProperties(PropertyFactory.visibility(value)) }
}

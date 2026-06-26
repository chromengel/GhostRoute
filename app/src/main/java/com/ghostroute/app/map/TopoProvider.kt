package com.ghostroute.app.map

import android.content.Context
import java.io.File

/**
 * Locates the optional offline topographic overlay — a hillshade raster PMTiles and a
 * contour-line vector PMTiles — in the app's private storage, pushed like the basemap
 * (see scripts/build-topo.sh). Absent until the user builds + installs it, in which case
 * the "Topographic" toggle simply doesn't appear.
 *
 * Both are read by MapLibre via the same `pmtiles://file://…` scheme as the basemap, so the
 * overlay stays 100% offline.
 */
object TopoProvider {

    private const val HILLSHADE_FILE = "hillshade.pmtiles"
    private const val CONTOURS_FILE = "contours.pmtiles"

    fun topoDir(context: Context): File = File(context.filesDir, "topo").apply { mkdirs() }

    private fun hillshadeFile(context: Context) = File(topoDir(context), HILLSHADE_FILE)
    private fun contoursFile(context: Context) = File(topoDir(context), CONTOURS_FILE)

    fun hasHillshade(context: Context): Boolean = hillshadeFile(context).let { it.exists() && it.length() > 0 }
    fun hasContours(context: Context): Boolean = contoursFile(context).let { it.exists() && it.length() > 0 }

    /** True if either overlay is present, so the toggle is worth showing. */
    fun isInstalled(context: Context): Boolean = hasHillshade(context) || hasContours(context)

    fun hillshadeUrl(context: Context): String = "pmtiles://file://" + hillshadeFile(context).absolutePath
    fun contoursUrl(context: Context): String = "pmtiles://file://" + contoursFile(context).absolutePath
}

package com.ghostroute.app.map

import android.content.Context
import java.io.File

/**
 * Locates the offline PMTiles basemap on the device and produces a MapLibre
 * style JSON that points at it.
 *
 * The basemap is intentionally NOT bundled in the APK: a per-region PMTiles file
 * is hundreds of MB (see plan §9). Instead the app expects the file under the
 * app's private files dir, where it can be pushed via adb or downloaded later:
 *
 *     adb push tennessee.pmtiles \
 *        /data/local/tmp/ && adb shell run-as com.ghostroute.app \
 *        cp /data/local/tmp/tennessee.pmtiles files/basemap/tennessee.pmtiles
 *
 * (or simpler during dev: app-specific external dir — see README).
 */
object BasemapProvider {

    const val BASEMAP_FILE_NAME = "tennessee.pmtiles"
    private const val STYLE_ASSET_DAY = "style/ghostroute-style.json"
    private const val STYLE_ASSET_NIGHT = "style/ghostroute-style-night.json"
    private const val BASEMAP_URL_TOKEN = "__BASEMAP_URL__"

    /** Directory the app reads the basemap from. Created on demand. */
    fun basemapDir(context: Context): File =
        File(context.filesDir, "basemap").apply { mkdirs() }

    fun basemapFile(context: Context): File =
        File(basemapDir(context), BASEMAP_FILE_NAME)

    fun isBasemapInstalled(context: Context): Boolean =
        basemapFile(context).let { it.exists() && it.length() > 0 }

    /**
     * Loads the style template from assets and substitutes the PMTiles source
     * URL. MapLibre Native reads PMTiles via the `pmtiles://` protocol wrapping
     * an inner resource URL — here a local `file://` path.
     */
    fun buildStyleJson(context: Context, night: Boolean = false): String {
        val pmtilesUrl = "pmtiles://file://" + basemapFile(context).absolutePath
        val asset = if (night) STYLE_ASSET_NIGHT else STYLE_ASSET_DAY
        val template = context.assets.open(asset).bufferedReader().use { it.readText() }
        return template.replace(BASEMAP_URL_TOKEN, pmtilesUrl)
    }
}

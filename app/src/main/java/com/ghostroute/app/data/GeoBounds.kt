package com.ghostroute.app.data

/**
 * A simple lat/lon bounding box (south/west/north/east).
 *
 * Default region is the Greater Knoxville, TN area the bundled basemap focuses on.
 * Make this user-configurable in the Settings phase.
 */
data class GeoBounds(
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
) {
    companion object {
        /**
         * Greater Knoxville, TN and the surrounding counties, so cameras appear for trips
         * across the metro area. Make this user-configurable in the Settings phase.
         */
        val DEFAULT_REGION = GeoBounds(
            minLat = 35.45,
            minLon = -84.35,
            maxLat = 36.20,
            maxLon = -83.55,
        )
    }
}

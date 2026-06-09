package com.ghostroute.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A surveillance camera (primarily ALPR/Flock readers) sourced from OpenStreetMap.
 *
 * Schema mirrors plan §5. `direction` is the compass bearing (degrees, 0 = north)
 * that the camera *reads* traffic from; null means omnidirectional / unknown, which
 * the routing model (Phase 4) must treat as always-counting.
 */
@Entity(tableName = "cameras")
data class CameraEntity(
    /** OSM element id, e.g. "node/123456". Stable across syncs. */
    @PrimaryKey val id: String,
    val lat: Double,
    val lon: Double,
    /** Bearing in degrees [0,360) the camera reads; null = omnidirectional/unknown. */
    val direction: Double?,
    /** ALPR | gunshot | camera | speed | dome | other */
    val type: String,
    /** e.g. "Flock Safety", an agency name; null if untagged. */
    val operator: String?,
    /** osm | manual | mesh */
    val source: String,
    /** epoch millis of the sync that last wrote this row. */
    val lastSynced: Long,
    /** OSM node last-edit time (epoch millis) from the element's `timestamp` meta —
     *  a per-camera "data freshness" hint, distinct from our own [lastSynced]. */
    val osmTimestamp: Long? = null,
) {
    companion object {
        const val TYPE_ALPR = "ALPR"            // license-plate reader (incl. Flock) — the thing we route around
        const val TYPE_GUNSHOT = "gunshot"      // gunshot detector (ShotSpotter / SoundThinking)
        const val TYPE_CAMERA = "camera"        // generic CCTV / public camera
        const val TYPE_SPEED = "speed"          // speed / red-light enforcement camera
        const val TYPE_DOME = "dome"            // dome camera
        const val TYPE_OTHER = "other"          // surveillance node with no type yet — low-confidence pin

        const val SOURCE_OSM = "osm"
        const val SOURCE_MANUAL = "manual"
        const val SOURCE_MESH = "mesh"

        /** Types GhostRoute actually routes AROUND — the plate-reading surveillance the app
         *  exists to dodge. Other types (gunshot/speed/CCTV/untyped) are shown but not avoided. */
        val AVOIDED_TYPES = setOf(TYPE_ALPR)
    }
}

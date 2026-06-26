package com.ghostroute.app.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow

/** Outcome of a camera-data sync, so the UI can report success/failure honestly. */
sealed interface SyncOutcome {
    /** @param cameraCount devices fetched for the area; @param newCount how many of those
     *  were NOT already on the map before this sync (brand-new placements). */
    data class Success(val cameraCount: Int, val newCount: Int, val syncedAt: Long) : SyncOutcome
    data class Error(val message: String) : SyncOutcome
}

/**
 * Single source of truth for camera data: reads from Room, refreshes from Overpass.
 *
 * The local DB is authoritative for rendering and (later) routing; the network is
 * only ever touched during an explicit or scheduled refresh, never at navigation time.
 */
class CameraRepository(
    context: Context,
    private val dao: CameraDao,
    private val overpass: OverpassClient = OverpassClient(),
) {
    private val prefs = context.applicationContext
        .getSharedPreferences("ghostroute_sync", Context.MODE_PRIVATE)

    fun observeCameras(): Flow<List<CameraEntity>> = dao.observeAll()

    /** Epoch millis of the last successful sync, or 0 if never synced. */
    fun lastSyncedMillis(): Long = prefs.getLong(KEY_LAST_SYNC, 0L)

    suspend fun cameraCount(): Int = dao.count()

    /**
     * Fetches ALPR cameras for [bounds] and replaces the OSM-sourced rows in that box.
     * Network/parse failures are caught and returned as [SyncOutcome.Error].
     */
    suspend fun refresh(bounds: GeoBounds = GeoBounds.DEFAULT_REGION): SyncOutcome =
        try {
            Log.i(TAG, "Camera sync starting for $bounds")
            val now = System.currentTimeMillis()
            val cameras = overpass.fetchAlprCameras(bounds, now)
            // Which ids were already on the map here? Anything fetched that wasn't is "new".
            val existing = dao.idsInBounds(
                bounds.minLat, bounds.minLon, bounds.maxLat, bounds.maxLon,
            ).toHashSet()
            val newCount = cameras.count { it.id !in existing }
            dao.replaceOsmInBounds(
                bounds.minLat, bounds.minLon, bounds.maxLat, bounds.maxLon, cameras,
            )
            prefs.edit().putLong(KEY_LAST_SYNC, now).apply()
            Log.i(TAG, "Camera sync OK: ${cameras.size} stored, $newCount new")
            SyncOutcome.Success(cameras.size, newCount, now)
        } catch (e: Exception) {
            Log.w(TAG, "Camera sync failed", e)
            SyncOutcome.Error(e.message ?: "Sync failed")
        }

    @Volatile
    private var coverageInProgress = false

    /**
     * Location-aware coverage: ensures the camera tile the user is in has been synced
     * recently, fetching it (plus a margin into neighbours) if not. This is what makes
     * coverage follow you — drive to Nashville and Nashville's cameras download on their
     * own, while Knoxville's stay cached. A no-op (returns null) when the current tile
     * is already fresh or a fetch is already running.
     *
     * @param force re-fetch even if the tile is fresh (used by the manual ↻ button).
     */
    suspend fun ensureCoverage(lat: Double, lon: Double, force: Boolean = false): SyncOutcome? {
        val ti = kotlin.math.floor(lat / TILE_DEG).toInt()
        val tj = kotlin.math.floor(lon / TILE_DEG).toInt()
        val key = "tile_${ti}_${tj}"
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(key, 0L) < STALE_MS) return null
        if (coverageInProgress) return null
        coverageInProgress = true
        try {
            val bounds = GeoBounds(
                minLat = ti * TILE_DEG - MARGIN_DEG,
                minLon = tj * TILE_DEG - MARGIN_DEG,
                maxLat = (ti + 1) * TILE_DEG + MARGIN_DEG,
                maxLon = (tj + 1) * TILE_DEG + MARGIN_DEG,
            )
            val outcome = refresh(bounds)
            if (outcome is SyncOutcome.Success) prefs.edit().putLong(key, now).apply()
            return outcome
        } finally {
            coverageInProgress = false
        }
    }

    companion object {
        private const val TAG = "GhostRouteSync"
        private const val KEY_LAST_SYNC = "last_sync_millis"

        /** Coverage tiling: ~0.25° ≈ 27 km cells, fetched with a ~27 km margin so a
         *  ~0.75° (≈ 80 km) box loads around you — proven to stay under Overpass limits. */
        private const val TILE_DEG = 0.25
        private const val MARGIN_DEG = 0.25
        private const val STALE_MS = 14L * 24 * 60 * 60 * 1000 // re-sync a tile every 2 weeks

        @Volatile
        private var instance: CameraRepository? = null

        fun get(context: Context): CameraRepository =
            instance ?: synchronized(this) {
                instance ?: CameraRepository(
                    context = context,
                    dao = GhostRouteDatabase.get(context).cameraDao(),
                ).also { instance = it }
            }
    }
}

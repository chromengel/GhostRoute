package com.ghostroute.app.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CameraDao {

    /** All cameras, observed reactively so the map updates as syncs land. */
    @Query("SELECT * FROM cameras")
    fun observeAll(): Flow<List<CameraEntity>>

    /** Cameras within a lat/lon bounding box (used for region-scoped rendering). */
    @Query(
        "SELECT * FROM cameras WHERE lat BETWEEN :minLat AND :maxLat " +
            "AND lon BETWEEN :minLon AND :maxLon",
    )
    fun observeInBounds(
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
    ): Flow<List<CameraEntity>>

    @Query("SELECT COUNT(*) FROM cameras")
    suspend fun count(): Int

    /** Ids of cameras currently within a bounding box — used to tell which rows a refresh
     *  brings in NEW (not already on the map) versus re-confirms. */
    @Query(
        "SELECT id FROM cameras WHERE lat BETWEEN :minLat AND :maxLat " +
            "AND lon BETWEEN :minLon AND :maxLon",
    )
    suspend fun idsInBounds(
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
    ): List<String>

    /** Insert-or-update; OSM ids are stable so re-syncs refresh existing rows. */
    @Upsert
    suspend fun upsertAll(cameras: List<CameraEntity>)

    /**
     * Replaces the OSM-sourced set for a bounding box in one transaction: clears the
     * old OSM rows in that box, then inserts the freshly fetched ones. Manual/mesh
     * cameras (other sources) are left untouched.
     */
    @androidx.room.Transaction
    suspend fun replaceOsmInBounds(
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
        cameras: List<CameraEntity>,
    ) {
        deleteOsmInBounds(minLat, minLon, maxLat, maxLon)
        upsertAll(cameras)
    }

    @Query(
        "DELETE FROM cameras WHERE source = 'osm' AND lat BETWEEN :minLat AND :maxLat " +
            "AND lon BETWEEN :minLon AND :maxLon",
    )
    suspend fun deleteOsmInBounds(
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
    )
}

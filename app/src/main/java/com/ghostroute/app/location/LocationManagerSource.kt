package com.ghostroute.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.core.content.getSystemService
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Streams device location using the platform [LocationManager] only.
 *
 * We deliberately do NOT use FusedLocationProviderClient: it lives in Google
 * Play Services, which is absent on GrapheneOS and is exactly the kind of
 * dependency GhostRoute bans (plan §3). LocationManager talks straight to the
 * GPS/network providers and works with no GMS.
 *
 * The caller is responsible for having been granted ACCESS_FINE_LOCATION (or at
 * least COARSE) before collecting — the @SuppressLint is intentional and the
 * SecurityException path is handled defensively.
 */
class LocationManagerSource(context: Context) {

    private val locationManager: LocationManager? = context.getSystemService()

    /** Best last-known fix across providers, or null if none/unavailable. */
    @SuppressLint("MissingPermission")
    fun lastKnownLocation(): Location? {
        val lm = locationManager ?: return null
        return try {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .mapNotNull { provider ->
                    if (lm.isProviderEnabled(provider)) lm.getLastKnownLocation(provider) else null
                }
                .maxByOrNull { it.time }
        } catch (_: SecurityException) {
            null
        }
    }

    /**
     * Cold flow of location updates. Registers GPS and (when present) network
     * providers; unregisters automatically when collection stops.
     *
     * @param minTimeMs minimum interval between updates
     * @param minDistanceM minimum movement before an update is delivered. Defaults to
     *   0 so fixes arrive on a time basis even while stationary — otherwise a nonzero
     *   distance filter starves a parked driver of updates (navigation sits on
     *   "Locating…" until they physically move that far, and the puck freezes at lights).
     */
    @SuppressLint("MissingPermission")
    fun locationUpdates(
        minTimeMs: Long = 1_000L,
        minDistanceM: Float = 0f,
    ): Flow<Location> = callbackFlow {
        val lm = locationManager
        if (lm == null) {
            close()
            return@callbackFlow
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(location)
            }

            // Required for older provider implementations; no-op on modern API.
            @Deprecated("Deprecated in API 29")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        try {
            // Emit an immediate fix so the map can center without waiting for GPS.
            lastKnownLocation()?.let { trySend(it) }

            var registered = false
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, minTimeMs, minDistanceM, listener,
                )
                registered = true
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, minTimeMs, minDistanceM, listener,
                )
                registered = true
            }
            if (!registered) {
                // No providers on right now; keep the flow open so updates arrive
                // if the user enables location while the screen is visible.
            }
        } catch (e: SecurityException) {
            close(e)
            return@callbackFlow
        }

        awaitClose { lm.removeUpdates(listener) }
    }
}

package com.ghostroute.app

import android.app.Application
import com.ghostroute.app.routing.RoutingService
import com.ghostroute.app.sync.CameraSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre

/**
 * Application entry point.
 *
 * MapLibre must be initialized once, with the application context, before any
 * MapView is created. We pass no tile-server/API-key configuration on purpose:
 * GhostRoute renders only from a local PMTiles file, so there is no default
 * online tile source and nothing to phone home to.
 */
class GhostRouteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        // Weekly background refresh of camera data (network-gated, KEEP existing).
        CameraSyncWorker.schedulePeriodic(this)
        // Warm the routing engine off the main thread so the first route is fast.
        CoroutineScope(Dispatchers.Default).launch { RoutingService.prewarm(this@GhostRouteApp) }
    }
}

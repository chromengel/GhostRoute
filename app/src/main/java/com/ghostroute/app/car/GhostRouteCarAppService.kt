package com.ghostroute.app.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Entry point for Android Auto (projected). The phone's Android Auto host binds to this
 * service to show GhostRoute on the car screen. This is pure AndroidX (`androidx.car.app`)
 * — no Google Play Services in the app itself; the host does the projecting.
 *
 * Declared in the manifest with the `androidx.car.app.category.NAVIGATION` category so
 * the car treats GhostRoute as a navigation app (full-screen map surface).
 */
class GhostRouteCarAppService : CarAppService() {

    // A sideloaded, personal app on the user's own device: allow any host that binds.
    // (A published app would pin Google's car-host signatures via HostValidator.Builder.)
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = GhostRouteSession()
}

package com.ghostroute.app.car

import com.ghostroute.app.routing.RoutePoint
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Process-wide bridge between the phone UI ([com.ghostroute.app.ui.map.MapViewModel])
 * and the Android Auto car screen ([NavMapScreen]). The car screen runs in the same
 * process but a different component than the Activity, so this object is how the route
 * the user picked on the phone reaches the map drawn on the car display.
 *
 * Cameras and the live location are pulled by the car screen directly from their own
 * singletons (so the car map works even if the phone UI was never opened); only the
 * chosen route lives here.
 */
object NavHub {

    @Volatile
    var routePoints: List<RoutePoint> = emptyList()
        private set

    /** Next-maneuver text while navigating (null when not navigating). */
    @Volatile
    var maneuverText: String? = null
        private set

    /** "12 min · 8 mi" style remaining estimate while navigating, or null. */
    @Volatile
    var etaText: String? = null
        private set

    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun setRoute(points: List<RoutePoint>) {
        routePoints = points
        notifyListeners()
    }

    fun setNavInfo(maneuver: String?, eta: String?) {
        maneuverText = maneuver
        etaText = eta
        notifyListeners()
    }

    fun clearRoute() {
        routePoints = emptyList()
        maneuverText = null
        etaText = null
        notifyListeners()
    }

    fun addListener(listener: () -> Unit) = listeners.add(listener)

    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    private fun notifyListeners() = listeners.forEach { it() }
}

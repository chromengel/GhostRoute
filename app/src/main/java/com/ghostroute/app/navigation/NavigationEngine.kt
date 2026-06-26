package com.ghostroute.app.navigation

import com.ghostroute.app.routing.Maneuver
import com.ghostroute.app.routing.RoutePoint
import com.ghostroute.app.routing.ScoredRoute
import kotlin.math.cos
import kotlin.math.sqrt

/** A navigation tick: what to show, and (optionally) what to speak. */
data class NavUpdate(
    val nextManeuver: Maneuver?,
    val distanceToManeuverM: Double,
    val distanceRemainingM: Double,
    val distanceTraveledM: Double,
    val timeRemainingMillis: Long,
    val offRoute: Boolean,
    val arrived: Boolean,
    val speak: String?,
)

/**
 * Drives turn-by-turn navigation along one chosen [ScoredRoute].
 *
 * Each location fix is snapped to the route polyline to get progress, the next
 * maneuver, and whether the driver has gone off-route. Voice prompts fire in two
 * stages with **speed-scaled lead time** — the late-prompt problem is the #1
 * complaint about existing apps (plan §9), so the lead distance grows with speed
 * (a highway turn is announced much earlier than a city one).
 */
class NavigationEngine(private val route: ScoredRoute) {

    private val points: List<RoutePoint> = route.points
    private val cumulative: DoubleArray = DoubleArray(points.size)
    private val totalDistanceM: Double

    // Each maneuver's distance along the route, in the engine's own (equirectangular) metric
    // so it compares apples-to-apples with `progress`. Built from the maneuver's precomputed
    // cumulative distance (which is monotonic and matches the blue line, since maneuvers are
    // now generated from the SAME path) rather than re-snapping each point independently —
    // independent snapping could land a turn on the wrong nearby segment and produce an
    // out-of-order value, which froze the next-turn name. A running max guarantees order.
    private val maneuverAlong: DoubleArray

    private val spokenPrepare = HashSet<Int>()
    private val spokenNow = HashSet<Int>()
    private var arrivedSpoken = false
    private var maxProgressM = 0.0

    init {
        var sum = 0.0
        for (i in 1 until points.size) {
            sum += distanceMeters(points[i - 1], points[i])
            cumulative[i] = sum
        }
        totalDistanceM = sum
        // Rescale the maneuvers' GraphHopper-measured distances into the engine's metric and
        // force them non-decreasing, so the next-turn pick always advances with you.
        val ghTotal = route.maneuvers.lastOrNull()?.distanceAlongRouteM ?: 0.0
        val scale = if (ghTotal > 1.0) totalDistanceM / ghTotal else 1.0
        maneuverAlong = DoubleArray(route.maneuvers.size)
        var running = 0.0
        for (i in route.maneuvers.indices) {
            running = maxOf(running, route.maneuvers[i].distanceAlongRouteM * scale)
            maneuverAlong[i] = running
        }
    }

    /** First spoken cue when navigation starts. */
    fun startCue(): String {
        val first = route.maneuvers.firstOrNull { it.sign != 0 } ?: route.maneuvers.firstOrNull()
        return if (first != null) "Starting navigation. ${first.text}." else "Starting navigation."
    }

    fun update(lat: Double, lon: Double, speedMps: Float): NavUpdate {
        val snap = snapToRoute(lat, lon)
        val progress = snap.alongM.coerceAtLeast(maxProgressM - BACKTRACK_TOLERANCE_M)
        if (progress > maxProgressM) maxProgressM = progress

        val distanceRemaining = (totalDistanceM - progress).coerceAtLeast(0.0)
        val timeRemaining = if (totalDistanceM > 0) {
            (route.durationMillis * (distanceRemaining / totalDistanceM)).toLong()
        } else {
            0L
        }
        val arrived = distanceRemaining <= ARRIVE_THRESHOLD_M
        val offRoute = snap.offRouteM > OFF_ROUTE_THRESHOLD_M

        // Next maneuver = the NEAREST real turn ahead of us (skip "continue"). Picking the
        // closest-ahead by distance (not the first in list order) means an out-of-order entry
        // can never trap the banner on a turn you've already passed.
        val nextIndex = route.maneuvers.indices
            .filter { i -> maneuverAlong[i] > progress + 1.0 && route.maneuvers[i].sign != 0 }
            .minByOrNull { maneuverAlong[it] } ?: -1
        val next = route.maneuvers.getOrNull(nextIndex)
        val distanceToManeuver = if (next != null) maneuverAlong[nextIndex] - progress else distanceRemaining

        val speak = chooseUtterance(
            nextIndex, next, distanceToManeuver, speedMps, arrived, offRoute,
        )

        return NavUpdate(next, distanceToManeuver, distanceRemaining, progress, timeRemaining, offRoute, arrived, speak)
    }

    private fun chooseUtterance(
        index: Int,
        next: Maneuver?,
        distanceToManeuver: Double,
        speedMps: Float,
        arrived: Boolean,
        offRoute: Boolean,
    ): String? {
        if (offRoute) return null // the ViewModel handles re-routing; don't announce turns
        if (arrived) {
            if (!arrivedSpoken) {
                arrivedSpoken = true
                return "You have arrived at your destination."
            }
            return null
        }
        if (next == null || index < 0) return null

        val speed = speedMps.toDouble().coerceAtLeast(MIN_SPEED_MPS)
        val prepareLead = (speed * PREPARE_LEAD_SECONDS).coerceIn(MIN_PREPARE_M, MAX_PREPARE_M)
        val nowLead = (speed * NOW_LEAD_SECONDS).coerceIn(MIN_NOW_M, MAX_NOW_M)

        if (distanceToManeuver <= nowLead && index !in spokenNow) {
            spokenNow.add(index)
            spokenPrepare.add(index) // suppress a late "prepare" if we jumped straight here
            return next.text
        }
        if (distanceToManeuver <= prepareLead && index !in spokenPrepare) {
            spokenPrepare.add(index)
            return "In ${spokenDistance(distanceToManeuver)}, ${next.text.replaceFirstChar { it.lowercase() }}"
        }
        return null
    }

    private class Snap(val alongM: Double, val offRouteM: Double)

    private fun snapToRoute(lat: Double, lon: Double): Snap {
        var bestPerp = Double.MAX_VALUE
        var bestAlong = 0.0
        for (i in 0 until points.size - 1) {
            val (perp, along) = projectOntoSegment(lat, lon, points[i], points[i + 1])
            if (perp < bestPerp) {
                bestPerp = perp
                bestAlong = cumulative[i] + along
            }
        }
        return Snap(bestAlong, bestPerp)
    }

    companion object {
        private const val OFF_ROUTE_THRESHOLD_M = 45.0
        private const val ARRIVE_THRESHOLD_M = 25.0
        private const val BACKTRACK_TOLERANCE_M = 30.0

        // Lead-time tuning. Prepare prompt ~14 s out (clamped), final prompt ~4 s out.
        private const val MIN_SPEED_MPS = 5.0 // assume ~11 mph if stationary/unknown
        private const val PREPARE_LEAD_SECONDS = 14.0
        private const val NOW_LEAD_SECONDS = 4.0
        private const val MIN_PREPARE_M = 150.0
        private const val MAX_PREPARE_M = 600.0
        private const val MIN_NOW_M = 30.0
        private const val MAX_NOW_M = 120.0

        private const val M_PER_DEG_LAT = 111_320.0

        /** Returns (perpendicular distance, distance along segment from a), in meters. */
        private fun projectOntoSegment(
            pLat: Double, pLon: Double, a: RoutePoint, b: RoutePoint,
        ): Pair<Double, Double> {
            val mPerDegLon = M_PER_DEG_LAT * cos(Math.toRadians(a.lat))
            val bx = (b.lon - a.lon) * mPerDegLon
            val by = (b.lat - a.lat) * M_PER_DEG_LAT
            val px = (pLon - a.lon) * mPerDegLon
            val py = (pLat - a.lat) * M_PER_DEG_LAT
            val segLen2 = bx * bx + by * by
            val t = if (segLen2 == 0.0) 0.0 else ((px * bx + py * by) / segLen2).coerceIn(0.0, 1.0)
            val dx = px - t * bx
            val dy = py - t * by
            return sqrt(dx * dx + dy * dy) to (t * sqrt(segLen2))
        }

        private fun distanceMeters(a: RoutePoint, b: RoutePoint): Double {
            val mPerDegLon = M_PER_DEG_LAT * cos(Math.toRadians(a.lat))
            val dx = (b.lon - a.lon) * mPerDegLon
            val dy = (b.lat - a.lat) * M_PER_DEG_LAT
            return sqrt(dx * dx + dy * dy)
        }

        /** Spoken distance in US units: feet under ~0.2 mi, else miles. */
        fun spokenDistance(meters: Double): String {
            val feet = meters * 3.28084
            if (feet < 1000) {
                val rounded = ((feet / 50).toInt() * 50).coerceAtLeast(50)
                return "$rounded feet"
            }
            val miles = meters / 1609.344
            return if (miles < 1.0) {
                when {
                    miles < 0.3 -> "a quarter mile"
                    miles < 0.6 -> "half a mile"
                    else -> "three quarters of a mile"
                }
            } else {
                "%.1f miles".format(miles)
            }
        }
    }
}

package com.ghostroute.app.routing

/** A single geometry vertex of a route. */
data class RoutePoint(val lat: Double, val lon: Double)

/** A computed route: total distance, travel time, and the polyline geometry. */
data class RouteResult(
    val distanceMeters: Double,
    val durationMillis: Long,
    val points: List<RoutePoint>,
)

/** Result of a routing request, so the UI can report success/failure honestly. */
sealed interface RouteOutcome {
    data class Success(val route: RouteResult) : RouteOutcome
    data class Error(val message: String) : RouteOutcome

    /** The on-device routing graph isn't installed yet. */
    data object GraphNotReady : RouteOutcome
}

/**
 * A single turn-by-turn maneuver along a route (Phase 5).
 *
 * [text] is ready to speak/display ("Turn left onto South Cusick Street").
 * [distanceAlongRouteM] is the cumulative distance from the route start to where
 * the maneuver happens, so navigation can compute the distance to the next turn.
 */
data class Maneuver(
    val sign: Int,
    val text: String,
    val streetName: String,
    val distanceAlongRouteM: Double,
    val lat: Double,
    val lon: Double,
) {
    companion object {
        const val SIGN_FINISH = 4
    }
}

/** One camera-scored alternative in a multi-route result (plan §6 step 4). */
data class ScoredRoute(
    val distanceMeters: Double,
    val durationMillis: Long,
    val camerasPassed: Int,
    val points: List<RoutePoint>,
    val maneuvers: List<Maneuver>,
    /** How hard this candidate avoided cameras: 0 = fastest, higher = more paranoid. */
    val penaltyPerCamera: Double,
    /** True if this route was reconstructed from a path the user habitually drives to this
     *  destination (learned automatically) — surfaced as "Your usual" and pre-selected. */
    val isLearned: Boolean = false,
)

/** Result of a multi-route (camera-aware) request. */
sealed interface AlternativesOutcome {
    data class Success(val routes: List<ScoredRoute>) : AlternativesOutcome
    data class Error(val message: String) : AlternativesOutcome
    data object GraphNotReady : AlternativesOutcome
}

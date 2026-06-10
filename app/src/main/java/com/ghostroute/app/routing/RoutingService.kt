package com.ghostroute.app.routing

import android.content.Context
import android.util.Log
import com.ghostroute.app.data.CameraEntity
import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.config.CHProfile
import com.graphhopper.config.Profile
import com.graphhopper.routing.AStar
import com.graphhopper.routing.AlternativeRouteCH
import com.graphhopper.routing.InstructionsFromEdges
import com.graphhopper.routing.Path
import com.graphhopper.routing.ev.Subnetwork
import com.graphhopper.routing.lm.LMApproximator
import com.graphhopper.routing.lm.LMConfig
import com.graphhopper.routing.lm.LandmarkStorage
import com.graphhopper.routing.querygraph.QueryGraph
import com.graphhopper.routing.querygraph.QueryRoutingCHGraph
import com.graphhopper.routing.util.DefaultSnapFilter
import com.graphhopper.routing.util.TraversalMode
import com.graphhopper.routing.weighting.Weighting
import com.graphhopper.storage.Graph
import com.graphhopper.util.Instruction
import com.graphhopper.util.InstructionList
import com.graphhopper.util.PMap
import com.graphhopper.util.PointList
import com.graphhopper.util.Translation
import com.graphhopper.util.TranslationMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * On-device offline routing via GraphHopper (graphhopper-core 7.0).
 *
 * The graph is built off-device by `scripts/build-graph.sh` (same GraphHopper
 * version) and pushed to app storage; like the basemap it is far too large to
 * bundle in the APK. Routing happens entirely on the phone — no network, ever.
 *
 * Profile config MUST match the build (car / fastest / CH). Phase 3 uses the
 * plain `fastest` weighting; GraphHopper's JSON custom models can't run on
 * Android (they JIT-compile via Janino), so Phase 4's camera penalty will be a
 * native Kotlin Weighting instead.
 */
object RoutingService {

    private const val TAG = "GhostRouteRouting"
    private const val GRAPH_DIR_NAME = "tennessee-gh"
    const val PROFILE = "car"

    /** Per-camera penalty (seconds) that dominates travel time → minimize cameras first. */
    private const val MINIMIZE_CAMERAS_PENALTY = 100_000.0

    /** Max route cards to show (Fastest … Fewest cameras, plus distinct corridors). */
    private const val MAX_ROUTE_CARDS = 4

    /** Upper bound on A* node expansions per search — a memory/time backstop against a
     *  degenerate (e.g. unreachable) target trying to scan the entire graph. */
    private const val MAX_VISITED_NODES = 2_000_000

    /** Two routes whose sampled geometry overlaps at least this much are "the same corridor". */
    private const val DUP_SIMILARITY = 0.80

    /** A route reduced to a set of ~110 m grid cells it passes through (for overlap tests). */
    private fun routeCells(points: List<RoutePoint>): Set<Long> {
        val cells = HashSet<Long>(points.size * 2)
        for (p in points) {
            val la = Math.round(p.lat * 1000).toInt()
            val lo = Math.round(p.lon * 1000).toInt()
            cells.add((la.toLong() shl 32) or (lo.toLong() and 0xffffffffL))
        }
        return cells
    }

    /** Fraction of the smaller route's cells that the larger route also covers (0..1). */
    private fun similarity(a: Set<Long>, b: Set<Long>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val (small, large) = if (a.size <= b.size) a to b else b to a
        var inter = 0
        for (c in small) if (c in large) inter++
        return inter.toDouble() / small.size
    }

    /**
     * Picks up to [max] route cards from the candidate pool so the user always sees a
     * meaningful spread — like Google/Waze — instead of one route or near-duplicates:
     *   1. Collapse near-identical geometries, keeping the best of each (fewer cameras,
     *      then faster, then the one that already carries turn-by-turn).
     *   2. Always include the global Fastest and the global Fewest-cameras route.
     *   3. Fill the rest with the most geographically DISTINCT remaining corridors.
     * Returned sorted by time (fastest first) so the card labels line up.
     */
    private fun selectDiverse(pool: List<ScoredRoute>, max: Int): List<ScoredRoute> {
        if (pool.size <= 1) return pool
        val cells = pool.map { routeCells(it.points) }
        val rank = compareBy<Int>(
            { pool[it].camerasPassed }, { pool[it].durationMillis }, { if (pool[it].maneuvers.isEmpty()) 1 else 0 },
        )
        val distinct = ArrayList<Int>()
        for (i in pool.indices.sortedWith(rank)) {
            if (distinct.none { similarity(cells[i], cells[it]) >= DUP_SIMILARITY }) distinct.add(i)
        }
        if (distinct.size <= max) return distinct.map { pool[it] }.sortedBy { it.durationMillis }

        val fastest = distinct.minByOrNull { pool[it].durationMillis }!!
        val fewest = distinct.minWithOrNull(
            compareBy({ pool[it].camerasPassed }, { pool[it].durationMillis }),
        )!!
        val chosen = LinkedHashSet<Int>()
        chosen.add(fastest)
        chosen.add(fewest)
        while (chosen.size < max) {
            val next = distinct.filter { it !in chosen }
                .maxByOrNull { cand -> 1.0 - chosen.maxOf { similarity(cells[cand], cells[it]) } }
                ?: break
            chosen.add(next)
        }
        return chosen.map { pool[it] }.sortedBy { it.durationMillis }
    }

    @Volatile
    private var hopper: GraphHopper? = null

    fun graphDir(context: Context): File =
        File(File(context.filesDir, "graph"), GRAPH_DIR_NAME)

    /** A built GraphHopper graph always has a `properties` file. */
    fun isGraphInstalled(context: Context): Boolean =
        File(graphDir(context), "properties").exists()

    /**
     * Computes a fastest car route between two points. Heavy work (graph load on
     * first call, then the search) runs off the main thread.
     */
    suspend fun route(
        context: Context,
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
    ): RouteOutcome = withContext(Dispatchers.Default) {
        if (!isGraphInstalled(context)) return@withContext RouteOutcome.GraphNotReady

        val gh = try {
            loadedHopper(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load routing graph", e)
            return@withContext RouteOutcome.Error("Couldn't load routing graph: ${e.message}")
        }

        try {
            val request = GHRequest(fromLat, fromLon, toLat, toLon).setProfile(PROFILE)
            val response = gh.route(request)
            if (response.hasErrors()) {
                val msg = response.errors.firstOrNull()?.message ?: "No route found"
                Log.w(TAG, "Route error: $msg")
                return@withContext RouteOutcome.Error(msg)
            }
            val best = response.best
            val points = ArrayList<RoutePoint>(best.points.size())
            for (i in 0 until best.points.size()) {
                points.add(RoutePoint(best.points.getLat(i), best.points.getLon(i)))
            }
            Log.i(TAG, "Route OK: ${"%.1f".format(best.distance / 1000)} km, ${best.time / 60000} min")
            RouteOutcome.Success(RouteResult(best.distance, best.time, points))
        } catch (e: Exception) {
            Log.e(TAG, "Routing failed", e)
            RouteOutcome.Error("Routing failed: ${e.message}")
        }
    }

    // Cached base weighting + camera exposure (exposure rebuilt only when the
    // camera set changes — building two edge-sized arrays isn't free).
    @Volatile
    private var baseWeighting: Weighting? = null

    @Volatile
    private var cachedExposure: CameraExposure? = null

    @Volatile
    private var landmarks: LandmarkStorage? = null

    /** GraphHopper's bundled turn-instruction translations; null → fallback text. */
    private val translation: Translation? by lazy {
        try {
            TranslationMap().doImport().getWithFallBack(Locale.US)
        } catch (e: Exception) {
            Log.w(TAG, "Instruction translations unavailable; using fallback text", e)
            null
        }
    }

    /**
     * Multi-route like Google/Waze, but camera-aware. Two candidate sources are merged:
     *
     *  1. **Distinct main corridors** — GraphHopper's CH alternative-route algorithm
     *     (`AlternativeRouteCH`, driven directly off the prepared CH graph) returns 2–3
     *     genuinely different roads (e.g. via the interstate vs. a surface route). These
     *     exist even when no cameras are involved, so the user always gets real choices.
     *  2. **Camera-avoidance variants** — a small penalty sweep (unidirectional A*+LM)
     *     that finds the quickest route at progressively lower camera exposure, including
     *     the camera-free option that is GhostRoute's whole point.
     *
     * Both pools are scored for camera count and handed to [selectDiverse], which always
     * surfaces the Fastest and the Fewest-cameras route plus the most distinct corridors.
     */
    suspend fun routeAlternatives(
        context: Context,
        cameras: List<CameraEntity>,
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
    ): AlternativesOutcome = withContext(Dispatchers.Default) {
        if (!isGraphInstalled(context)) return@withContext AlternativesOutcome.GraphNotReady

        val gh = try {
            loadedHopper(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load routing graph", e)
            return@withContext AlternativesOutcome.Error("Couldn't load routing graph: ${e.message}")
        }

        try {
            val base = baseWeighting ?: gh.createWeighting(gh.getProfile(PROFILE), PMap())
                .also { baseWeighting = it }
            val exposure = exposureFor(gh, cameras)

            // Camera-avoidance penalty sweep (seconds/camera): a gradient from light
            // avoidance up to the camera-free route (GhostRoute's whole point). Penalty 0
            // (the plain fastest) isn't here — the CH corridors below already provide the
            // fastest route plus variety.
            val penalties = listOf(50.0, 200.0, 800.0, MINIMIZE_CAMERAS_PENALTY)

            // Pre-warm shared read-only state so the parallel searches below only READ it.
            landmarksFor(gh, base)

            // 1) CH corridors first — fast (contraction hierarchy), and each already knows
            //    how many cameras it passes.
            val tSearch = System.nanoTime()
            val corridors = chAlternatives(gh, base, fromLat, fromLon, toLat, toLon, cameras)

            // 2) Camera-avoidance sweep ONLY when it can help. Each sweep route is a ~1–2 s
            //    unidirectional A* on this device, so skip it whenever a CH corridor is
            //    already camera-free (nothing to avoid) — or when there are no cameras at
            //    all. We still run it if CH produced nothing, so we always return a route.
            val corridorMinCams = corridors.minOfOrNull { it.camerasPassed } ?: Int.MAX_VALUE
            val needSweep = corridors.isEmpty() || (cameras.isNotEmpty() && corridorMinCams > 0)
            val sweep = if (needSweep) {
                penalties.map { penalty ->
                    async {
                        // Build maneuvers HERE, from this route's own path, so the turn-by-turn
                        // names always match the exact geometry we draw + navigate. (Recomputing
                        // them later, at nav start, could resolve a slightly different path if the
                        // camera set changed in between — which made the next-turn banner show a
                        // street that didn't match the blue line and froze.)
                        val flex = flexRoute(gh, base, fromLat, fromLon, toLat, toLon, exposure, penalty, withManeuvers = true)
                            ?: return@async null
                        ScoredRoute(
                            distanceMeters = flex.distance,
                            durationMillis = flex.time,
                            camerasPassed = CameraExposure.camerasPassed(flex.points, cameras),
                            points = flex.points.toRoutePoints(),
                            maneuvers = flex.maneuvers,
                            penaltyPerCamera = penalty,
                        )
                    }
                }.awaitAll().filterNotNull()
            } else {
                emptyList()
            }
            val pool = corridors + sweep
            if (pool.isEmpty()) return@withContext AlternativesOutcome.Error("No route found")
            val searchMs = (System.nanoTime() - tSearch) / 1_000_000

            val shown = selectDiverse(pool, MAX_ROUTE_CARDS)

            Log.i(
                TAG,
                "Alternatives corridors=${corridors.size} sweep=${sweep.size} shown=${shown.size} searchMs=$searchMs " +
                    "shown=[${shown.joinToString { "${(it.distanceMeters / 1000).roundToInt()}km/${it.durationMillis / 60000}min/${it.camerasPassed}cam" }}]",
            )
            AlternativesOutcome.Success(shown)
        } catch (e: Throwable) {
            // Throwable, not Exception: a pathological search can raise OutOfMemoryError,
            // and we must surface that as a recoverable error rather than let it kill the
            // whole app. References to the search structures drop out of scope here, so the
            // GC reclaims them and the app stays usable.
            Log.e(TAG, "Camera-aware routing failed", e)
            AlternativesOutcome.Error("Routing failed: ${e.message}")
        }
    }

    /**
     * Distinct main corridors via GraphHopper's **CH alternative-route** algorithm,
     * invoked at the low level so it uses only the prepared Contraction Hierarchy — NOT
     * landmarks. (The high-level `gh.route(algorithm=alternative_route)` tries to load LM
     * and collides with our manual landmark storage: "DataAccess landmarks_car already
     * created". Driving `AlternativeRouteCH` off a [QueryRoutingCHGraph] sidesteps that.)
     *
     * Each corridor carries its own turn-by-turn (its `Path` is already in hand), so the
     * navigated route needs no penalty reconstruction. Returns empty on any failure, so
     * the camera penalty sweep alone still yields a usable result.
     */
    private fun chAlternatives(
        gh: GraphHopper,
        base: Weighting,
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        cameras: List<CameraEntity>,
    ): List<ScoredRoute> {
        val chGraph = gh.chGraphs[PROFILE] ?: return emptyList()
        val subnetwork = gh.encodingManager.getBooleanEncodedValue(Subnetwork.key(PROFILE))
        val snapFilter = DefaultSnapFilter(base, subnetwork)
        val from = gh.locationIndex.findClosest(fromLat, fromLon, snapFilter)
        val to = gh.locationIndex.findClosest(toLat, toLon, snapFilter)
        if (!from.isValid || !to.isValid) return emptyList()
        val queryGraph = QueryGraph.create(gh.baseGraph, listOf(from, to))
        val queryCH = QueryRoutingCHGraph(chGraph, queryGraph)

        val pmap = PMap()
            .putObject("alternative_route.max_paths", 3)
            .putObject("alternative_route.max_weight_factor", 1.8)
            .putObject("alternative_route.max_share_factor", 0.7)
        val paths = try {
            AlternativeRouteCH(queryCH, pmap).calcPaths(from.closestNode, to.closestNode)
        } catch (e: Exception) {
            Log.w(TAG, "CH alternative routes unavailable; using camera sweep only", e)
            return emptyList()
        }

        return paths.filter { it.isFound }.map { path ->
            val pts = path.calcPoints()
            ScoredRoute(
                distanceMeters = path.distance,
                durationMillis = path.time,
                camerasPassed = CameraExposure.camerasPassed(pts, cameras),
                points = pts.toRoutePoints(),
                maneuvers = buildManeuvers(gh, queryGraph, base, path),
                penaltyPerCamera = -1.0, // distinct corridor → carries its own maneuvers
            )
        }
    }

    /**
     * Computes turn-by-turn directions for ONE route (identified by its avoidance
     * [penaltyPerCamera] and the same from/to), on demand. Called when the user starts
     * navigating, so the route-options screen doesn't pay to build directions for
     * routes it never navigates. Deterministic — reproduces the same path as the card.
     */
    suspend fun maneuversFor(
        context: Context,
        cameras: List<CameraEntity>,
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        penaltyPerCamera: Double,
    ): List<Maneuver> = withContext(Dispatchers.Default) {
        if (!isGraphInstalled(context)) return@withContext emptyList()
        val gh = try {
            loadedHopper(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load routing graph for maneuvers", e)
            return@withContext emptyList()
        }
        val base = baseWeighting ?: gh.createWeighting(gh.getProfile(PROFILE), PMap())
            .also { baseWeighting = it }
        val exposure = exposureFor(gh, cameras)
        flexRoute(gh, base, fromLat, fromLon, toLat, toLon, exposure, penaltyPerCamera, withManeuvers = true)
            ?.maneuvers ?: emptyList()
    }

    /**
     * Reconstructs a route that follows a remembered [chain] of waypoints (start … destination)
     * by routing each consecutive leg and stitching them together — points, turn-by-turn, and
     * totals. This reproduces the corridor the user habitually drives while still giving real
     * snapping and directions. Legs use the plain fastest weighting (the waypoints ARE the
     * user's chosen path); the result is scored for camera count like any other route. Returns
     * null if the graph is missing or any leg can't be routed.
     */
    suspend fun routeViaPoints(
        context: Context,
        cameras: List<CameraEntity>,
        chain: List<RoutePoint>,
    ): ScoredRoute? = withContext(Dispatchers.Default) {
        if (chain.size < 2) return@withContext null
        if (!isGraphInstalled(context)) return@withContext null
        val gh = try {
            loadedHopper(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load graph for learned route", e)
            return@withContext null
        }
        val base = baseWeighting ?: gh.createWeighting(gh.getProfile(PROFILE), PMap())
            .also { baseWeighting = it }
        val exposure = exposureFor(gh, cameras)

        val allPoints = ArrayList<RoutePoint>()
        val allManeuvers = ArrayList<Maneuver>()
        var cumDist = 0.0
        var cumTime = 0L
        for (i in 0 until chain.size - 1) {
            val a = chain[i]
            val b = chain[i + 1]
            val leg = flexRoute(gh, base, a.lat, a.lon, b.lat, b.lon, exposure, 0.0, withManeuvers = true)
                ?: return@withContext null
            val legPts = leg.points.toRoutePoints()
            if (i == 0) allPoints.addAll(legPts) else allPoints.addAll(legPts.drop(1))
            val isLast = i == chain.size - 2
            for (mv in leg.maneuvers) {
                // Drop the "arrive" pin of every intermediate leg — only the final leg arrives.
                if (!isLast && mv.sign == Maneuver.SIGN_FINISH) continue
                allManeuvers.add(mv.copy(distanceAlongRouteM = mv.distanceAlongRouteM + cumDist))
            }
            cumDist += leg.distance
            cumTime += leg.time
        }
        if (allPoints.size < 2) return@withContext null
        ScoredRoute(
            distanceMeters = cumDist,
            durationMillis = cumTime,
            camerasPassed = CameraExposure.camerasPassed(allPoints.toPointList(), cameras),
            points = allPoints,
            maneuvers = allManeuvers,
            penaltyPerCamera = -1.0,
            isLearned = true,
        )
    }

    private fun List<RoutePoint>.toPointList(): PointList =
        PointList(size, false).also { pl -> forEach { pl.add(it.lat, it.lon) } }

    /**
     * Warms the routing engine off the main thread at app startup: loads the graph +
     * landmarks, triggers the one-time (~6 s) instruction-translation import, and runs a
     * throwaway route to JIT the search paths — so the user's FIRST real route is fast
     * instead of paying all of that cold.
     */
    suspend fun prewarm(context: Context) = withContext(Dispatchers.Default) {
        if (!isGraphInstalled(context)) return@withContext
        try {
            val gh = loadedHopper(context)
            val base = baseWeighting ?: gh.createWeighting(gh.getProfile(PROFILE), PMap())
                .also { baseWeighting = it }
            landmarksFor(gh, base)
            val exposure = CameraExposure.build(gh, emptyList())
            flexRoute(gh, base, 35.96, -83.92, 35.92, -83.99, exposure, 0.0, withManeuvers = true)
            Log.i(TAG, "prewarm complete")
        } catch (e: Exception) {
            Log.w(TAG, "prewarm failed", e)
        }
    }

    private fun exposureFor(gh: GraphHopper, cameras: List<CameraEntity>): CameraExposure {
        val signature = CameraExposure.signatureOf(cameras)
        cachedExposure?.let { if (it.signature == signature) return it }
        return CameraExposure.build(gh, cameras).also { cachedExposure = it }
    }

    private class FlexRoute(
        val distance: Double,
        val time: Long,
        val points: PointList,
        val maneuvers: List<Maneuver>,
    )

    /**
     * Flexible (non-CH) route on the base graph so a custom [Weighting] applies.
     * The camera weighting is constructed *after* the QueryGraph so virtual edges
     * (the snap edges at start/destination) can be resolved to their original
     * edge's exposure — otherwise a camera on the first/last edge is unavoidable.
     */
    private fun flexRoute(
        gh: GraphHopper,
        base: Weighting,
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        exposure: CameraExposure,
        penalty: Double,
        withManeuvers: Boolean,
    ): FlexRoute? {
        val subnetwork = gh.encodingManager.getBooleanEncodedValue(Subnetwork.key(PROFILE))
        val snapFilter = DefaultSnapFilter(base, subnetwork)
        val from = gh.locationIndex.findClosest(fromLat, fromLon, snapFilter)
        val to = gh.locationIndex.findClosest(toLat, toLon, snapFilter)
        if (!from.isValid || !to.isValid) return null
        val queryGraph = QueryGraph.create(gh.baseGraph, listOf(from, to))

        val weighting: Weighting = if (penalty <= 0.0) {
            base
        } else {
            CameraAvoidanceWeighting(base, exposure.forward, exposure.reverse, gh.baseGraph.edges, penalty)
        }

        // UNIDIRECTIONAL A* + a Landmark heuristic. Unidirectional is REQUIRED: the
        // camera weighting is direction-asymmetric, and AStarBidirection's backward
        // search would read the wrong fwd/rev exposure, letting penalties escape (a
        // camera on the route would not be avoided). The LM lower bound (built on the
        // fastest weighting) stays admissible because the penalty only ever increases
        // edge weight, keeping the search fast (a few thousand nodes, single-digit ms).
        val algo = AStar(queryGraph, weighting, TraversalMode.NODE_BASED)
        // Bound the search so an unreachable/degenerate target can't explore the whole
        // graph and exhaust memory — it returns "no path" instead. Real routes inside the
        // TN+WNC extract stay far under this even cross-state.
        algo.setMaxVisitedNodes(MAX_VISITED_NODES)
        landmarksFor(gh, base)?.let {
            algo.setApproximation(LMApproximator.forLandmarks(queryGraph, base, it, 8))
        }
        val path = algo.calcPath(from.closestNode, to.closestNode)
        if (!path.isFound) return null
        val maneuvers = if (withManeuvers) buildManeuvers(gh, queryGraph, base, path) else emptyList()
        return FlexRoute(path.distance, path.time, path.calcPoints(), maneuvers)
    }

    /** Converts a route into turn-by-turn maneuvers with cumulative along-route distances. */
    private fun buildManeuvers(gh: GraphHopper, graph: Graph, base: Weighting, path: Path): List<Maneuver> {
        val instructions = try {
            InstructionsFromEdges.calcInstructions(path, graph, base, gh.encodingManager, translation ?: dummyTranslation)
        } catch (e: Exception) {
            Log.w(TAG, "Instruction generation failed", e)
            return emptyList()
        }
        return instructionsToManeuvers(instructions)
    }

    /** Shared instruction-list → [Maneuver] conversion (used by both flexible routes and
     *  the CH alternative-route response). */
    private fun instructionsToManeuvers(instructions: InstructionList): List<Maneuver> {
        val maneuvers = ArrayList<Maneuver>(instructions.size)
        var cumulative = 0.0
        for (ins in instructions) {
            val pts = ins.points
            maneuvers.add(
                Maneuver(
                    sign = ins.sign,
                    text = maneuverText(ins),
                    streetName = ins.name ?: "",
                    distanceAlongRouteM = cumulative,
                    lat = if (pts.size() > 0) pts.getLat(0) else 0.0,
                    lon = if (pts.size() > 0) pts.getLon(0) else 0.0,
                ),
            )
            cumulative += ins.distance
        }
        return maneuvers
    }

    private fun maneuverText(ins: Instruction): String {
        translation?.let { tr ->
            runCatching { return ins.getTurnDescription(tr).replaceFirstChar { it.uppercase() } }
        }
        return fallbackManeuverText(ins.sign, ins.name ?: "")
    }

    private val dummyTranslation: Translation = object : Translation {
        override fun tr(key: String, vararg params: Any?): String = key
        override fun asMap(): Map<String, String> = emptyMap()
        override fun getLocale(): Locale = Locale.US
        override fun getLanguage(): String = "en"
    }

    private fun fallbackManeuverText(sign: Int, street: String): String {
        val base = when (sign) {
            Instruction.TURN_LEFT -> "Turn left"
            Instruction.TURN_SHARP_LEFT -> "Turn sharp left"
            Instruction.TURN_SLIGHT_LEFT -> "Keep slightly left"
            Instruction.TURN_RIGHT -> "Turn right"
            Instruction.TURN_SHARP_RIGHT -> "Turn sharp right"
            Instruction.TURN_SLIGHT_RIGHT -> "Keep slightly right"
            Instruction.KEEP_LEFT -> "Keep left"
            Instruction.KEEP_RIGHT -> "Keep right"
            Instruction.U_TURN_LEFT, Instruction.U_TURN_RIGHT, Instruction.U_TURN_UNKNOWN -> "Make a U-turn"
            Instruction.FINISH -> "Arrive at destination"
            else -> "Continue"
        }
        return if (street.isNotBlank() && sign != Instruction.FINISH) "$base onto $street" else base
    }

    /** Loads the prepared landmark storage once (the graph dir contains it).
     *  Synchronized — concurrent callers (prewarm + the parallel route searches) would
     *  otherwise each try to load it, throwing "DataAccess landmarks_car already created". */
    private fun landmarksFor(gh: GraphHopper, base: Weighting): LandmarkStorage? {
        landmarks?.let { return it }
        return synchronized(landmarksLock) {
            landmarks ?: try {
                gh.lmPreparationHandler
                    .load(listOf(LMConfig(PROFILE, base)), gh.baseGraph, gh.encodingManager)
                    .firstOrNull()
                    .also { landmarks = it }
            } catch (e: Exception) {
                Log.w(TAG, "No landmarks available; routing will fall back to slow search", e)
                null
            }
        }
    }

    private val landmarksLock = Any()

    private fun PointList.toRoutePoints(): List<RoutePoint> =
        ArrayList<RoutePoint>(size()).also { list ->
            for (i in 0 until size()) list.add(RoutePoint(getLat(i), getLon(i)))
        }

    private fun loadedHopper(context: Context): GraphHopper {
        hopper?.let { return it }
        return synchronized(this) {
            // RAM_STORE (the default) keeps the graph in on-heap primitive arrays, which
            // the routing hot loop reads at full speed. We tried graph.dataaccess=MMAP to
            // save heap, but on Android's runtime each memory-mapped read is ~600x slower
            // than an array access, making a route take 15+ s — unusable. So the graph is
            // kept lean (8 landmarks, ~374 MB) to fit the 512 MB largeHeap with room for
            // the search instead.
            hopper ?: GraphHopper().apply {
                setProfiles(Profile(PROFILE).setVehicle("car").setWeighting("fastest"))
                chPreparationHandler.setCHProfiles(CHProfile(PROFILE))
                graphHopperLocation = graphDir(context).absolutePath
                importOrLoad() // graph exists on device → loads (never imports)
            }.also { hopper = it }
        }
    }
}

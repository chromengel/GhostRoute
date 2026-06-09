import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.GraphHopperConfig
import com.graphhopper.config.CHProfile
import com.graphhopper.config.LMProfile
import com.graphhopper.config.Profile
import com.graphhopper.routing.AStarBidirection
import com.graphhopper.routing.AlternativeRouteCH
import com.graphhopper.routing.InstructionsFromEdges
import com.graphhopper.routing.querygraph.QueryRoutingCHGraph
import com.graphhopper.routing.ev.Subnetwork
import com.graphhopper.routing.lm.LMApproximator
import com.graphhopper.routing.lm.LMConfig
import com.graphhopper.routing.lm.LandmarkStorage
import com.graphhopper.routing.querygraph.QueryGraph
import com.graphhopper.routing.querygraph.VirtualEdgeIteratorState
import com.graphhopper.routing.util.DefaultSnapFilter
import com.graphhopper.routing.util.TraversalMode
import com.graphhopper.routing.weighting.Weighting
import com.graphhopper.util.AngleCalc
import com.graphhopper.util.DistanceCalcEarth
import com.graphhopper.util.EdgeIteratorState
import com.graphhopper.util.FetchMode
import com.graphhopper.util.GHUtility
import com.graphhopper.util.PMap
import com.graphhopper.util.PointList
import com.graphhopper.util.TranslationMap
import com.graphhopper.util.shapes.BBox
import com.graphhopper.storage.index.LocationIndex
import com.graphhopper.storage.index.LocationIndexTree
import kotlin.math.abs
import kotlin.math.cos
import kotlin.system.exitProcess

/**
 * GhostRoute graph tooling + a desktop prototype of the Phase 4 camera-aware
 * routing engine. The engine logic here (CameraExposure + CameraAvoidanceWeighting
 * + camerasPassed) mirrors what the Android app uses, so we can validate it on the
 * real Tennessee graph before porting.
 *
 * Usage:
 *   <pbf> <graphDir>        build the graph + smoke-test route (used by build-graph.sh)
 *   cameratest <graphDir>   load the graph and validate camera-aware routing
 */
fun main(args: Array<String>) {
    when (args.getOrNull(0)) {
        "cameratest" -> cameraTest(args[1])
        "snaptest" -> snapTest(args[1])
        "alttest" -> altTest(args[1])
        "turntest" -> turnTest(args[1])
        else -> importGraph(args[0], args[1])
    }
}

/**
 * Validates the production `RoutingService.chAlternatives` path: drive `AlternativeRouteCH`
 * directly off the prepared CH graph via a [QueryRoutingCHGraph] (no LM → no
 * "landmarks_car already created"), and confirm it returns 2–3 GENUINELY DISTINCT
 * corridors for a real city-to-city trip (the whole point of "show me multiple routes").
 */
private fun altTest(graphDir: String) {
    val hopper = loadHopper(graphDir)
    val base = hopper.createWeighting(hopper.getProfile("car"), PMap())

    // Knoxville → a nearby town: has real alternatives (US-129 vs I-140).
    val fromLat = 35.9606; val fromLon = -83.9207
    val toLat = 35.7565; val toLon = -83.9705

    val chGraph = hopper.chGraphs["car"] ?: error("no CH graph")
    val sf = DefaultSnapFilter(base, hopper.encodingManager.getBooleanEncodedValue(Subnetwork.key("car")))
    val from = hopper.locationIndex.findClosest(fromLat, fromLon, sf)
    val to = hopper.locationIndex.findClosest(toLat, toLon, sf)
    require(from.isValid && to.isValid) { "snap failed" }
    val qg = QueryGraph.create(hopper.baseGraph, listOf(from, to))
    val queryCH = QueryRoutingCHGraph(chGraph, qg)

    val pmap = PMap()
        .putObject("alternative_route.max_paths", 3)
        .putObject("alternative_route.max_weight_factor", 1.8)
        .putObject("alternative_route.max_share_factor", 0.7)
    val t0 = System.nanoTime()
    val paths = AlternativeRouteCH(queryCH, pmap).calcPaths(from.closestNode, to.closestNode)
    val ms = (System.nanoTime() - t0) / 1_000_000
    println("CH alternatives: ${paths.size} path(s) in ${ms}ms")

    val tr = TranslationMap().doImport().getWithFallBack(java.util.Locale.US)
    val cellsList = paths.map { p ->
        val pts = p.calcPoints()
        val cells = HashSet<Long>()
        for (i in 0 until pts.size()) {
            val la = Math.round(pts.getLat(i) * 1000).toInt()
            val lo = Math.round(pts.getLon(i) * 1000).toInt()
            cells.add((la.toLong() shl 32) or (lo.toLong() and 0xffffffffL))
        }
        // Confirm turn-by-turn builds from a CH-derived Path (the new eager-maneuver path).
        val instr = InstructionsFromEdges.calcInstructions(p, qg, base, hopper.encodingManager, tr)
        println("  path: %.1f km, %d min, %d points, %d maneuvers (first: %s)".format(
            p.distance / 1000, p.time / 60000, pts.size(), instr.size,
            if (instr.size > 0) instr[0].getTurnDescription(tr) else "—"))
        cells
    }
    // Pairwise overlap — distinct corridors should share well under ~0.8 of their geometry.
    for (i in cellsList.indices) for (j in i + 1 until cellsList.size) {
        val a = cellsList[i]; val b = cellsList[j]
        val (small, large) = if (a.size <= b.size) a to b else b to a
        val inter = small.count { it in large }
        println("  overlap path$i↔path$j = %.2f".format(inter.toDouble() / small.size))
    }
    hopper.close()
}

/**
 * Diagnoses the "wrong street name in the next-turn banner" report. Builds a route's
 * maneuvers exactly like the app (InstructionsFromEdges → name/sign/cumulative), prints
 * them, then SIMULATES driving the polyline and, at each sampled point, applies the same
 * next-maneuver selection the on-device NavigationEngine uses — so we can see whether the
 * banner would name the upcoming turn's road correctly or pick the wrong/next-next one.
 */
private fun turnTest(graphDir: String) {
    val hopper = loadHopper(graphDir)
    val base = hopper.createWeighting(hopper.getProfile("car"), PMap())
    val tr = TranslationMap().doImport().getWithFallBack(java.util.Locale.US)

    // A multi-turn in-town route within the supported region.
    val fromLat = 35.7565; val fromLon = -83.9705
    val toLat = 35.7460; val toLon = -83.9290

    val sf = DefaultSnapFilter(base, hopper.encodingManager.getBooleanEncodedValue(Subnetwork.key("car")))
    val s1 = hopper.locationIndex.findClosest(fromLat, fromLon, sf)
    val s2 = hopper.locationIndex.findClosest(toLat, toLon, sf)
    require(s1.isValid && s2.isValid) { "snap failed" }
    val qg = QueryGraph.create(hopper.baseGraph, listOf(s1, s2))
    val astar = com.graphhopper.routing.AStar(qg, base, TraversalMode.NODE_BASED)
    val path = astar.calcPath(s1.closestNode, s2.closestNode)
    require(path.isFound) { "no path" }
    val instr = InstructionsFromEdges.calcInstructions(path, qg, base, hopper.encodingManager, tr)

    // Maneuver list as the APP builds it: distanceAlongRouteM = cumulative BEFORE this
    // instruction (= the turn point); text = getTurnDescription (names the road turned onto).
    data class Mvr(val sign: Int, val name: String, val text: String, val alongM: Double, val lat: Double, val lon: Double)
    val mvrs = ArrayList<Mvr>()
    var cum = 0.0
    for (ins in instr) {
        val p = ins.points
        mvrs.add(Mvr(ins.sign, ins.name ?: "", ins.getTurnDescription(tr), cum,
            if (p.size() > 0) p.getLat(0) else 0.0, if (p.size() > 0) p.getLon(0) else 0.0))
        cum += ins.distance
    }
    println("ROUTE %.2f km, ${instr.size} maneuvers (GH total=%.0fm)".format(path.distance / 1000, path.distance))
    mvrs.forEachIndexed { i, m ->
        println("  [%2d] sign=%3d alongM=%6.0f  name='%s'  text='%s'".format(i, m.sign, m.alongM, m.name, m.text))
    }

    // Build the polyline + the engine's equirectangular cumulative (this is what progress
    // is measured in on-device), so we can compare it to the GH-based maneuver alongM.
    val pts = path.calcPoints()
    val lat = DoubleArray(pts.size()) { pts.getLat(it) }
    val lon = DoubleArray(pts.size()) { pts.getLon(it) }
    val mPerDegLat = 111_320.0
    fun seg(i: Int): Double {
        val mPerDegLon = mPerDegLat * cos(Math.toRadians(lat[i]))
        val dx = (lon[i + 1] - lon[i]) * mPerDegLon
        val dy = (lat[i + 1] - lat[i]) * mPerDegLat
        return Math.sqrt(dx * dx + dy * dy)
    }
    val cumE = DoubleArray(pts.size())
    for (i in 1 until pts.size()) cumE[i] = cumE[i - 1] + seg(i - 1)
    println("equirect total=%.0fm vs GH total=%.0fm (Δ=%.1f%%)".format(
        cumE.last(), path.distance, 100 * (cumE.last() - path.distance) / path.distance))

    // Simulate driving: at each maneuver's turn point, what does the engine pick as "next"?
    // (engine rule: first maneuver with alongM > progress+1 && sign != 0)
    println("SIMULATED next-turn banner just BEFORE each turn point:")
    for (k in mvrs.indices) {
        val m = mvrs[k]
        if (m.sign == 0) continue
        val progress = (m.alongM - 30.0).coerceAtLeast(0.0) // ~30 m before the turn
        val nextIdx = mvrs.indexOfFirst { it.alongM > progress + 1.0 && it.sign != 0 }
        val picked = mvrs.getOrNull(nextIdx)
        val flag = if (picked != null && picked.alongM == m.alongM) "OK " else ">>>"
        println("  $flag at ~30m before turn[$k] '${m.text}' -> banner would show: '${picked?.text}'")
    }

    // ---- CH-corridor path: the route the app DEFAULTS to (fewest cameras) usually comes
    // from AlternativeRouteCH, whose Path is built from contraction-hierarchy SHORTCUT edges.
    // Dump its maneuvers to see whether instruction generation lands turns on the right roads.
    println("\n==== CH CORRIDOR maneuvers (AlternativeRouteCH, as the app uses) ====")
    val chGraph = hopper.chGraphs["car"]
    if (chGraph == null) {
        println("  no CH graph")
    } else {
        val queryCH = QueryRoutingCHGraph(chGraph, qg)
        val pmap = PMap()
            .putObject("alternative_route.max_paths", 3)
            .putObject("alternative_route.max_weight_factor", 1.8)
            .putObject("alternative_route.max_share_factor", 0.7)
        val chPaths = AlternativeRouteCH(queryCH, pmap).calcPaths(s1.closestNode, s2.closestNode)
        chPaths.filter { it.isFound }.forEachIndexed { ci, cp ->
            println("  -- corridor $ci: %.2f km, %d edges --".format(cp.distance / 1000, cp.calcEdges().size))
            val ci2 = InstructionsFromEdges.calcInstructions(cp, qg, base, hopper.encodingManager, tr)
            var c2 = 0.0
            ci2.forEachIndexed { j, ins ->
                println("    [%2d] sign=%3d alongM=%6.0f name='%s' text='%s'".format(
                    j, ins.sign, c2, ins.name ?: "", ins.getTurnDescription(tr)))
                c2 += ins.distance
            }
        }
    }
    hopper.close()
}

private fun loadHopper(graphDir: String): GraphHopper {
    val hopper = GraphHopper()
    hopper.setProfiles(Profile("car").setVehicle("car").setWeighting("fastest"))
    hopper.chPreparationHandler.setCHProfiles(CHProfile("car"))
    // NOTE: do NOT setLMProfiles here — that makes importOrLoad load the LM data
    // internally, which then collides with our explicit handler.load() below.
    // We load the prepared landmark files ourselves (see cameraTest).
    hopper.graphHopperLocation = graphDir
    hopper.importOrLoad()
    return hopper
}

private fun importGraph(pbf: String, graphDir: String) {
    println("Importing $pbf -> $graphDir")
    // Build via GraphHopperConfig so we can set prepare.lm.landmarks=8. The default is
    // 16 landmarks, which makes a 166 MB landmark file (graph ~457 MB) — too big to load
    // into the app's 512 MB Android heap with room for the routing search (it OOM-crashed).
    // 8 landmarks halves that file to ~83 MB (graph ~374 MB); the heuristic is slightly
    // weaker but the on-device A* still visits only a few thousand nodes. The app loads 8
    // ACTIVE landmarks (LMApproximator forLandmarks(..., 8)), so 8 total uses them all.
    val cfg = GraphHopperConfig()
        .putObject("datareader.file", pbf)
        .putObject("graph.location", graphDir)
        .putObject("prepare.min_network_size", 200)
        .putObject("prepare.lm.landmarks", 8)
        .putObject("import.osm.ignored_highways", "")
    cfg.setProfiles(listOf(Profile("car").setVehicle("car").setWeighting("fastest")))
    cfg.setCHProfiles(listOf(CHProfile("car")))
    cfg.setLMProfiles(listOf(LMProfile("car")))
    val hopper = GraphHopper().init(cfg)
    hopper.importOrLoad()

    val request = GHRequest(35.7565, -83.9705, 35.6745, -83.7568).setProfile("car")
    val response = hopper.route(request)
    if (response.hasErrors()) {
        System.err.println("SMOKE-TEST ROUTE FAILED: ${response.errors}")
        hopper.close()
        exitProcess(1)
    }
    val best = response.best
    println(
        "SMOKE-TEST ROUTE OK: %.1f km, %d min, %d points".format(
            best.distance / 1000.0, best.time / 60_000, best.points.size(),
        ),
    )
    hopper.close()
}

// ---------------------------------------------------------------------------
// Phase 4 engine prototype
// ---------------------------------------------------------------------------

private const val RADIUS_M = 75.0
private const val THETA_DEG = 45.0

data class Cam(val id: String, val lat: Double, val lon: Double, val dir: Double?)

private fun angleDiff(a: Double, b: Double): Double {
    val d = abs(a - b) % 360.0
    return if (d > 180.0) 360.0 - d else d
}

private val DIST = DistanceCalcEarth()
private val ANGLE = AngleCalc()

private fun pointToSegMeters(
    pLat: Double, pLon: Double, aLat: Double, aLon: Double, bLat: Double, bLon: Double,
): Double {
    var min = minOf(DIST.calcDist(pLat, pLon, aLat, aLon), DIST.calcDist(pLat, pLon, bLat, bLon))
    if (DIST.validEdgeDistance(pLat, pLon, aLat, aLon, bLat, bLon)) {
        val normal = DIST.calcNormalizedEdgeDistance(pLat, pLon, aLat, aLon, bLat, bLon)
        min = minOf(min, DIST.calcDenormalizedDist(normal))
    }
    return min
}

/** Builds per-directed-edge camera exposure counts (forward/reverse by edge id). */
private fun buildExposure(hopper: GraphHopper, cams: List<Cam>): Pair<IntArray, IntArray> {
    val graph = hopper.baseGraph
    val index = hopper.locationIndex as LocationIndexTree
    val fwd = IntArray(graph.edges)
    val rev = IntArray(graph.edges)

    for (cam in cams) {
        val dLat = RADIUS_M / 111_320.0
        val dLon = RADIUS_M / (111_320.0 * cos(Math.toRadians(cam.lat)))
        val bbox = BBox(cam.lon - dLon, cam.lon + dLon, cam.lat - dLat, cam.lat + dLat)
        val edgeIds = HashSet<Int>()
        index.query(bbox, object : LocationIndex.Visitor {
            override fun onEdge(edgeId: Int) { edgeIds.add(edgeId) }
        })
        for (edgeId in edgeIds) {
            if (edgeId >= graph.edges) continue
            val edge = graph.getEdgeIteratorState(edgeId, Int.MIN_VALUE)
            val geom = edge.fetchWayGeometry(FetchMode.ALL)
            // Mirror the app: test EVERY in-radius segment, not just the closest.
            var fwdHit = false
            var revHit = false
            for (i in 0 until geom.size() - 1) {
                val d = pointToSegMeters(
                    cam.lat, cam.lon,
                    geom.getLat(i), geom.getLon(i), geom.getLat(i + 1), geom.getLon(i + 1),
                )
                if (d > RADIUS_M) continue
                if (cam.dir == null) { fwdHit = true; revHit = true; break }
                val bearing = ANGLE.calcAzimuth(geom.getLat(i), geom.getLon(i), geom.getLat(i + 1), geom.getLon(i + 1))
                if (angleDiff(bearing, cam.dir) <= THETA_DEG) fwdHit = true
                if (angleDiff((bearing + 180.0) % 360.0, cam.dir) <= THETA_DEG) revHit = true
            }
            if (fwdHit) fwd[edgeId]++
            if (revHit) rev[edgeId]++
        }
    }
    return fwd to rev
}

/** FastestWeighting + a per-camera penalty; no Janino/custom-model needed. */
private class CameraAvoidanceWeighting(
    private val base: Weighting,
    private val fwd: IntArray,
    private val rev: IntArray,
    private val penaltyPerCamera: Double,
) : Weighting {
    override fun calcEdgeWeight(edge: EdgeIteratorState, reverse: Boolean): Double {
        val w = base.calcEdgeWeight(edge, reverse)
        if (w == Double.POSITIVE_INFINITY) return w
        val id = edge.edge
        val exp = if (id < fwd.size) (if (reverse) rev[id] else fwd[id]) else 0
        return w + exp * penaltyPerCamera
    }

    override fun edgeHasNoAccess(edge: EdgeIteratorState, reverse: Boolean) = base.edgeHasNoAccess(edge, reverse)
    override fun calcEdgeMillis(edge: EdgeIteratorState, reverse: Boolean) = base.calcEdgeMillis(edge, reverse)
    override fun calcTurnWeight(inEdge: Int, viaNode: Int, outEdge: Int) = base.calcTurnWeight(inEdge, viaNode, outEdge)
    override fun calcTurnMillis(inEdge: Int, viaNode: Int, outEdge: Int) = base.calcTurnMillis(inEdge, viaNode, outEdge)
    override fun hasTurnCosts() = base.hasTurnCosts()
    override fun getMinWeight(distance: Double) = base.getMinWeight(distance)
    override fun getName() = "camera_avoid"
}

/**
 * Virtual-edge-aware camera weighting — the fix under test. Mirrors the app's
 * production [com.ghostroute.app.routing.CameraAvoidanceWeighting]: snap (virtual)
 * edges, whose ids run past the base arrays, are resolved to their original edge's
 * exposure (passed in as [virtualFwd]/[virtualRev], indexed by virtualId-baseEdges).
 */
private class CameraAvoidanceWeightingV2(
    private val base: Weighting,
    private val fwd: IntArray,
    private val rev: IntArray,
    private val baseEdgeCount: Int,
    private val penaltyPerCamera: Double,
) : Weighting {
    override fun calcEdgeWeight(edge: EdgeIteratorState, reverse: Boolean): Double {
        val w = base.calcEdgeWeight(edge, reverse)
        if (w == Double.POSITIVE_INFINITY) return w
        val exp = exposureOf(edge, reverse)
        return w + exp * penaltyPerCamera
    }

    private fun exposureOf(edge: EdgeIteratorState, reverse: Boolean): Int {
        val id = edge.edge
        if (id < baseEdgeCount) return if (reverse) rev[id] else fwd[id]
        dbgVirtualSeen++
        // Virtual (snap) edge: detach to the underlying VirtualEdgeIteratorState
        // (no allocation — it just returns the iterator's current edge) and resolve
        // the original edge it was carved from, honoring direction via the key parity.
        val st = (edge as? VirtualEdgeIteratorState)
            ?: (edge.detach(false) as? VirtualEdgeIteratorState)
            ?: return 0
        dbgVirtualResolved++
        val ok = st.originalEdgeKey
        val oid = GHUtility.getEdgeFromEdgeKey(ok)
        if (oid !in fwd.indices) return 0
        val origForward = (ok and 1) == 0
        val baseToAdj = if (origForward) fwd[oid] else rev[oid]
        val adjToBase = if (origForward) rev[oid] else fwd[oid]
        val result = if (reverse) adjToBase else baseToAdj
        if (result > 0) dbgVirtualNonzero++
        if (fwd[oid] > 0 || rev[oid] > 0) dbgVirtualOnMarked++
        return result
    }

    override fun edgeHasNoAccess(edge: EdgeIteratorState, reverse: Boolean) = base.edgeHasNoAccess(edge, reverse)
    override fun calcEdgeMillis(edge: EdgeIteratorState, reverse: Boolean) = base.calcEdgeMillis(edge, reverse)
    override fun calcTurnWeight(inEdge: Int, viaNode: Int, outEdge: Int) = base.calcTurnWeight(inEdge, viaNode, outEdge)
    override fun calcTurnMillis(inEdge: Int, viaNode: Int, outEdge: Int) = base.calcTurnMillis(inEdge, viaNode, outEdge)
    override fun hasTurnCosts() = base.hasTurnCosts()
    override fun getMinWeight(distance: Double) = base.getMinWeight(distance)
    override fun getName() = "camera_avoid_v2"
}

/**
 * Reproduces the user's exact failing case: route their from→to past the single real
 * corridor camera, comparing the OLD (virtual-blind) weighting to the NEW one. If the
 * camera sits on a snap edge, OLD reports 1 cam at 100k (can't avoid) and NEW should
 * drop it (or prove it genuinely unavoidable).
 */
private fun snapTest(graphDir: String) {
    val hopper = loadHopper(graphDir)
    val base = hopper.createWeighting(hopper.getProfile("car"), PMap())
    val lms = hopper.lmPreparationHandler
        .load(listOf(LMConfig("car", base)), hopper.baseGraph, hopper.encodingManager)
        .firstOrNull()

    val fromLat = 35.74549020733684; val fromLon = -83.94823984242976
    val toLat = 35.750194798034954; val toLon = -83.96587173786952
    val cams = listOf(Cam("node/13266183555", 35.745198, -83.962576, 305.0))

    val (fwd, rev) = buildExposure(hopper, cams)
    val baseEdges = hopper.baseGraph.edges
    println("exposure: nonzero fwd=${fwd.count { it > 0 }} rev=${rev.count { it > 0 }} (baseEdges=$baseEdges)")

    val fast = route(hopper, base, lms, fromLat, fromLon, toLat, toLon, base)!!
    println("FASTEST:  %.2f km, cams=%d".format(lastDistance / 1000, camerasPassed(fast, cams)))

    val oldPts = route(hopper, base, lms, fromLat, fromLon, toLat, toLon, CameraAvoidanceWeighting(base, fwd, rev, 100_000.0))!!
    println("OLD 100k: %.2f km, cams=%d".format(lastDistance / 1000, camerasPassed(oldPts, cams)))

    // NEW: virtual-aware weighting resolves snap edges lazily (the fix under test).
    dbgVirtualSeen = 0; dbgVirtualResolved = 0; dbgVirtualNonzero = 0; dbgVirtualOnMarked = 0
    val newW = CameraAvoidanceWeightingV2(base, fwd, rev, baseEdges, 100_000.0)
    val newPts = route(hopper, base, lms, fromLat, fromLon, toLat, toLon, newW)!!
    println("NEW 100k: %.2f km, cams=%d".format(lastDistance / 1000, camerasPassed(newPts, cams)))
    println("DIAG virtual: seen=$dbgVirtualSeen resolved=$dbgVirtualResolved nonzeroExp=$dbgVirtualNonzero onMarkedOrig=$dbgVirtualOnMarked")
    // NEW route's edges near the camera, with TRAVEL direction (edge-key parity) and
    // the exposure the weighting actually reads for that direction.
    for (e in lastPathEdges ?: emptyList()) {
        val g = e.fetchWayGeometry(FetchMode.ALL)
        var cd = Double.MAX_VALUE
        for (i in 0 until g.size() - 1) cd = minOf(cd, pointToSegMeters(35.745198, -83.962576, g.getLat(i), g.getLon(i), g.getLat(i + 1), g.getLon(i + 1)))
        if (cd > 90.0) continue
        val eid = e.edge
        val travelReverse = (e.edgeKey and 1) == 1
        val exp = if (eid < baseEdges) (if (travelReverse) rev[eid] else fwd[eid]) else -99
        println("  NEW edge id=$eid virtual=${eid >= baseEdges} closest=%.1fm travelReverse=$travelReverse fwd=${if (eid < baseEdges) fwd[eid] else -1} rev=${if (eid < baseEdges) rev[eid] else -1} -> weightingReads=$exp".format(cd))
    }

    // Same NEW weighting but WITHOUT the LM heuristic (plain bidirectional Dijkstra).
    val newNoLm = route(hopper, base, null, fromLat, fromLon, toLat, toLon, CameraAvoidanceWeightingV2(base, fwd, rev, baseEdges, 100_000.0))!!
    println("NEW 100k NO-LM: %.2f km, cams=%d".format(lastDistance / 1000, camerasPassed(newNoLm, cams)))

    // UNIDIRECTIONAL A* + LM with the directional weighting — its reverse flag always
    // matches travel direction, so a directional penalty is read correctly.
    run {
        val w = CameraAvoidanceWeightingV2(base, fwd, rev, baseEdges, 100_000.0)
        val sf = DefaultSnapFilter(base, hopper.encodingManager.getBooleanEncodedValue(Subnetwork.key("car")))
        val s1 = hopper.locationIndex.findClosest(fromLat, fromLon, sf)
        val s2 = hopper.locationIndex.findClosest(toLat, toLon, sf)
        val qg = QueryGraph.create(hopper.baseGraph, listOf(s1, s2))
        val a = com.graphhopper.routing.AStar(qg, w, TraversalMode.NODE_BASED)
        lms?.let { a.setApproximation(LMApproximator.forLandmarks(qg, base, it, 8)) }
        val t0 = System.nanoTime()
        val p = a.calcPath(s1.closestNode, s2.closestNode)
        println("NEW 100k UNIDIR-A*+LM: %.2f km, cams=%d, visited=%d in %dms".format(
            p.distance / 1000, camerasPassed(p.calcPoints(), cams), a.visitedNodes, (System.nanoTime() - t0) / 1_000_000))
    }

    // Perf sanity: unidirectional A*+LM on a long cross-region route.
    run {
        val w = CameraAvoidanceWeightingV2(base, fwd, rev, baseEdges, 100_000.0)
        val sf = DefaultSnapFilter(base, hopper.encodingManager.getBooleanEncodedValue(Subnetwork.key("car")))
        val s1 = hopper.locationIndex.findClosest(35.7565, -83.9705, sf)
        val s2 = hopper.locationIndex.findClosest(35.6745, -83.7568, sf)
        val qg = QueryGraph.create(hopper.baseGraph, listOf(s1, s2))
        val a = com.graphhopper.routing.AStar(qg, w, TraversalMode.NODE_BASED)
        lms?.let { a.setApproximation(LMApproximator.forLandmarks(qg, base, it, 8)) }
        val t0 = System.nanoTime()
        val p = a.calcPath(s1.closestNode, s2.closestNode)
        println("PERF long-route UNIDIR-A*+LM: %.1f km, visited=%d in %dms".format(p.distance / 1000, a.visitedNodes, (System.nanoTime() - t0) / 1_000_000))
    }

    // Is the camera even AVOIDABLE? Treat it as omnidirectional (penalize both ways)
    // with the new weighting. If this still passes it, no alternate road exists in
    // the graph — it's a genuine dead-end-style constraint, not a penalty bug.
    val omni = listOf(Cam("omni", 35.745198, -83.962576, null))
    val (ofwd, orev) = buildExposure(hopper, omni)
    val omniW = CameraAvoidanceWeightingV2(base, ofwd, orev, baseEdges, 1_000_000.0)
    val omniPts = route(hopper, base, lms, fromLat, fromLon, toLat, toLon, omniW)!!
    println("OMNI 1M:  %.2f km, cams=%d (omni-passed=%d)".format(lastDistance / 1000, camerasPassed(omniPts, cams), camerasPassed(omniPts, omni)))

    // Where does the fastest route actually pass the camera, and how close?
    var minD = Double.MAX_VALUE; var minI = -1; var minBearing = 0.0
    for (i in 0 until fast.size() - 1) {
        val d = pointToSegMeters(35.745198, -83.962576, fast.getLat(i), fast.getLon(i), fast.getLat(i + 1), fast.getLon(i + 1))
        if (d < minD) { minD = d; minI = i; minBearing = ANGLE.calcAzimuth(fast.getLat(i), fast.getLon(i), fast.getLat(i + 1), fast.getLon(i + 1)) }
    }
    println("DIAG closest approach: %.1f m at pt %d, travelBearing=%.0f camDir=305 angleDiff=%.0f".format(minD, minI, minBearing, angleDiff(minBearing, 305.0)))
    println("DIAG marked-fwd edges=${fwd.count { it > 0 }} marked-rev=${rev.count { it > 0 }}; omni marked-fwd=${ofwd.count { it > 0 }} marked-rev=${orev.count { it > 0 }}")

    // Inspect the FASTEST path's actual edges near the camera.
    route(hopper, base, lms, fromLat, fromLon, toLat, toLon, base)
    val edges = lastPathEdges ?: emptyList()
    println("DIAG fastest path uses ${edges.size} edges; ones within 90m of camera:")
    for (e in edges) {
        val g = e.fetchWayGeometry(FetchMode.ALL)
        var cd = Double.MAX_VALUE; var bear = 0.0
        for (i in 0 until g.size() - 1) {
            val d = pointToSegMeters(35.745198, -83.962576, g.getLat(i), g.getLon(i), g.getLat(i + 1), g.getLon(i + 1))
            if (d < cd) { cd = d; bear = ANGLE.calcAzimuth(g.getLat(i), g.getLon(i), g.getLat(i + 1), g.getLon(i + 1)) }
        }
        if (cd <= 90.0) {
            val eid = e.edge
            val virtual = eid >= baseEdges
            val fwdExp = if (!virtual) fwd[eid] else -1
            val revExp = if (!virtual) rev[eid] else -1
            println("  edge id=$eid virtual=$virtual closest=%.1fm storedBearing=%.0f fwdExp=$fwdExp revExp=$revExp (fwdMatch=%s revMatch=%s)".format(
                cd, bear,
                angleDiff(bear, 305.0) <= THETA_DEG,
                angleDiff((bear + 180.0) % 360.0, 305.0) <= THETA_DEG,
            ))
        }
    }

    hopper.close()
}

private fun route(
    hopper: GraphHopper,
    base: Weighting,
    lms: LandmarkStorage?,
    fromLat: Double, fromLon: Double, toLat: Double, toLon: Double,
    weighting: Weighting,
): PointList? {
    val snapFilter = DefaultSnapFilter(base, hopper.encodingManager.getBooleanEncodedValue(Subnetwork.key("car")))
    val snapFrom = hopper.locationIndex.findClosest(fromLat, fromLon, snapFilter)
    val snapTo = hopper.locationIndex.findClosest(toLat, toLon, snapFilter)
    if (!snapFrom.isValid || !snapTo.isValid) return null
    val queryGraph = QueryGraph.create(hopper.baseGraph, listOf(snapFrom, snapTo))
    val algo = AStarBidirection(queryGraph, weighting, TraversalMode.NODE_BASED)
    if (lms != null) algo.setApproximation(LMApproximator.forLandmarks(queryGraph, base, lms, 8))
    val started = System.nanoTime()
    val path = algo.calcPath(snapFrom.closestNode, snapTo.closestNode)
    System.err.println("DEBUG route visited=${algo.visitedNodes} in ${(System.nanoTime() - started) / 1_000_000}ms")
    if (!path.isFound) return null
    lastPathEdges = path.calcEdges()
    return path.calcPoints().also { lastDistance = path.distance; lastTime = path.time }
}

private var lastDistance = 0.0
private var lastTime = 0L
private var lastPathEdges: List<EdgeIteratorState>? = null

// Diagnostics for virtual-edge resolution in CameraAvoidanceWeightingV2.
private var dbgVirtualSeen = 0
private var dbgVirtualResolved = 0
private var dbgVirtualNonzero = 0
private var dbgVirtualOnMarked = 0

/** Counts distinct cameras a route passes (within radius + matching travel direction). */
private fun camerasPassed(points: PointList, cams: List<Cam>): Int {
    var count = 0
    for (cam in cams) {
        var passed = false
        for (i in 0 until points.size() - 1) {
            val d = pointToSegMeters(cam.lat, cam.lon, points.getLat(i), points.getLon(i), points.getLat(i + 1), points.getLon(i + 1))
            if (d <= RADIUS_M) {
                if (cam.dir == null) { passed = true; break }
                val bearing = ANGLE.calcAzimuth(points.getLat(i), points.getLon(i), points.getLat(i + 1), points.getLon(i + 1))
                if (angleDiff(bearing, cam.dir) <= THETA_DEG) { passed = true; break }
            }
        }
        if (passed) count++
    }
    return count
}

private fun cameraTest(graphDir: String) {
    val hopper = loadHopper(graphDir)
    val base = hopper.createWeighting(hopper.getProfile("car"), PMap())
    val lms = hopper.lmPreparationHandler
        .load(listOf(LMConfig("car", base)), hopper.baseGraph, hopper.encodingManager)
        .firstOrNull()
    System.err.println("DEBUG landmarks loaded: ${lms != null} count=${lms?.landmarkCount}")

    val fromLat = 35.7565; val fromLon = -83.9705 // TN test origin
    val toLat = 35.6745; val toLon = -83.7568      // TN test destination

    // Fastest route (no penalty) → place sample cameras directly on it.
    val fastPts = route(hopper, base, lms, fromLat, fromLon, toLat, toLon, base) ?: error("no fastest route")
    val fastDist = lastDistance; val fastTime = lastTime
    val omniCams = listOf(0.3, 0.5, 0.7).map { f ->
        val i = (fastPts.size() * f).toInt()
        Cam("omni$f", fastPts.getLat(i), fastPts.getLon(i), null)
    }
    println("FASTEST: %.1f km, %d min, cameras=%d".format(fastDist / 1000, fastTime / 60000, camerasPassed(fastPts, omniCams)))

    // Avoidance route with omnidirectional cameras on the fastest path.
    val (ofwd, orev) = buildExposure(hopper, omniCams)
    val t0 = System.nanoTime()
    val avoidPts = route(hopper, base, lms, fromLat, fromLon, toLat, toLon, CameraAvoidanceWeighting(base, ofwd, orev, 100_000.0))
        ?: error("no avoid route")
    println("AVOID(aggressive 100k): %.1f km, %d min, cameras=%d in %dms".format(lastDistance / 1000, lastTime / 60000, camerasPassed(avoidPts, omniCams), (System.nanoTime() - t0) / 1_000_000))

    // Directional check: a camera reading the OPPOSITE direction of travel should NOT count.
    val midIdx = fastPts.size() / 2
    val travelBearing = ANGLE.calcAzimuth(
        fastPts.getLat(midIdx - 1), fastPts.getLon(midIdx - 1), fastPts.getLat(midIdx), fastPts.getLon(midIdx),
    )
    val oppositeCam = listOf(Cam("opp", fastPts.getLat(midIdx), fastPts.getLon(midIdx), (travelBearing + 180.0) % 360.0))
    val sameCam = listOf(Cam("same", fastPts.getLat(midIdx), fastPts.getLon(midIdx), travelBearing))
    println("DIRECTIONAL: opposite-facing cameras passed=${camerasPassed(fastPts, oppositeCam)} (expect 0), same-facing=${camerasPassed(fastPts, sameCam)} (expect 1)")

    // Turn-by-turn instructions (Phase 5) for the fastest route.
    val sf = DefaultSnapFilter(base, hopper.encodingManager.getBooleanEncodedValue(Subnetwork.key("car")))
    val s1 = hopper.locationIndex.findClosest(fromLat, fromLon, sf)
    val s2 = hopper.locationIndex.findClosest(toLat, toLon, sf)
    val qg = QueryGraph.create(hopper.baseGraph, listOf(s1, s2))
    val astar = AStarBidirection(qg, base, TraversalMode.NODE_BASED)
    lms?.let { astar.setApproximation(LMApproximator.forLandmarks(qg, base, it, 8)) }
    val instrPath = astar.calcPath(s1.closestNode, s2.closestNode)
    val tr = TranslationMap().doImport().getWithFallBack(java.util.Locale.US)
    val instructions = InstructionsFromEdges.calcInstructions(instrPath, qg, base, hopper.encodingManager, tr)
    println("INSTRUCTIONS: ${instructions.size} maneuvers")
    instructions.take(6).forEach { ins ->
        println("  sign=${ins.sign} '${ins.getTurnDescription(tr)}' ${ins.distance.toInt()}m")
    }

    hopper.close()
}

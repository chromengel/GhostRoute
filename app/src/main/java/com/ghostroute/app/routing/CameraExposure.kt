package com.ghostroute.app.routing

import com.ghostroute.app.data.CameraEntity
import com.graphhopper.GraphHopper
import com.graphhopper.storage.index.LocationIndex
import com.graphhopper.storage.index.LocationIndexTree
import com.graphhopper.util.AngleCalc
import com.graphhopper.util.DistanceCalcEarth
import com.graphhopper.util.FetchMode
import com.graphhopper.util.PointList
import com.graphhopper.util.shapes.BBox
import kotlin.math.abs
import kotlin.math.cos

/**
 * Computes, for a set of cameras, how much each directed graph edge is "seen".
 *
 * A camera contributes to an edge if the edge passes within [RADIUS_M] AND the
 * edge's travel bearing is within ±[THETA_DEG] of the camera's read direction
 * (omnidirectional cameras always count). Exposure is stored per direction
 * ([forward]/[reverse], indexed by edge id) and consumed by
 * [CameraAvoidanceWeighting]. Validated end-to-end in scripts/graphtool.
 */
class CameraExposure private constructor(
    val forward: IntArray,
    val reverse: IntArray,
    val signature: Int,
) {
    companion object {
        const val RADIUS_M = 75.0
        const val THETA_DEG = 45.0

        private val DIST = DistanceCalcEarth()
        private val ANGLE = AngleCalc()

        /** Identifies a camera set so a cached exposure can be reused unchanged. */
        fun signatureOf(cameras: List<CameraEntity>): Int =
            cameras.fold(cameras.size) { acc, c -> acc * 31 + c.id.hashCode() }

        fun build(hopper: GraphHopper, cameras: List<CameraEntity>): CameraExposure {
            val graph = hopper.baseGraph
            val index = hopper.locationIndex as LocationIndexTree
            val edgeCount = graph.edges
            val fwd = IntArray(edgeCount)
            val rev = IntArray(edgeCount)

            for (camera in cameras) {
                val dLat = RADIUS_M / 111_320.0
                val dLon = RADIUS_M / (111_320.0 * cos(Math.toRadians(camera.lat)))
                val bbox = BBox(camera.lon - dLon, camera.lon + dLon, camera.lat - dLat, camera.lat + dLat)
                val edgeIds = HashSet<Int>()
                index.query(bbox, object : LocationIndex.Visitor {
                    override fun onEdge(edgeId: Int) { edgeIds.add(edgeId) }
                })
                for (edgeId in edgeIds) {
                    if (edgeId >= edgeCount) continue
                    val edge = graph.getEdgeIteratorState(edgeId, Int.MIN_VALUE)
                    val geom = edge.fetchWayGeometry(FetchMode.ALL)
                    // OMNIDIRECTIONAL: any edge passing within RADIUS_M of the camera is
                    // penalized in BOTH directions. ALPR `direction` tags in OSM are
                    // ambiguous/unreliable (often the way the camera points, not the way
                    // traffic flows), and for a privacy app the safe default is to avoid a
                    // camera on your road regardless of which way you pass it. (Kept fwd/rev
                    // arrays so the symmetric data still flows through the weighting.)
                    var within = false
                    for (i in 0 until geom.size() - 1) {
                        val d = pointToSegmentMeters(
                            camera.lat, camera.lon,
                            geom.getLat(i), geom.getLon(i), geom.getLat(i + 1), geom.getLon(i + 1),
                        )
                        if (d <= RADIUS_M) { within = true; break }
                    }
                    if (within) {
                        fwd[edgeId]++
                        rev[edgeId]++
                    }
                }
            }
            return CameraExposure(fwd, rev, signatureOf(cameras))
        }

        /**
         * Counts distinct cameras a finished route passes — within [RADIUS_M] of the
         * route, OMNIDIRECTIONALLY (any direction). Matches [build]'s penalty so the
         * displayed count agrees with what the router avoids. Geometry-based (not
         * edge-id based); run once per candidate route, not in the search hot loop.
         */
        fun camerasPassed(points: PointList, cameras: List<CameraEntity>): Int {
            var count = 0
            for (camera in cameras) {
                var passed = false
                var i = 0
                while (i < points.size() - 1 && !passed) {
                    val d = pointToSegmentMeters(
                        camera.lat, camera.lon,
                        points.getLat(i), points.getLon(i), points.getLat(i + 1), points.getLon(i + 1),
                    )
                    if (d <= RADIUS_M) passed = true
                    i++
                }
                if (passed) count++
            }
            return count
        }

        private fun angleDiff(a: Double, b: Double): Double {
            val d = abs(a - b) % 360.0
            return if (d > 180.0) 360.0 - d else d
        }

        private fun pointToSegmentMeters(
            pLat: Double, pLon: Double, aLat: Double, aLon: Double, bLat: Double, bLon: Double,
        ): Double {
            var min = minOf(DIST.calcDist(pLat, pLon, aLat, aLon), DIST.calcDist(pLat, pLon, bLat, bLon))
            if (DIST.validEdgeDistance(pLat, pLon, aLat, aLon, bLat, bLon)) {
                val normalized = DIST.calcNormalizedEdgeDistance(pLat, pLon, aLat, aLon, bLat, bLon)
                min = minOf(min, DIST.calcDenormalizedDist(normalized))
            }
            return min
        }
    }
}

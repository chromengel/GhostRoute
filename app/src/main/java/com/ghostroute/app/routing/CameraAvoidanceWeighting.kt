package com.ghostroute.app.routing

import com.graphhopper.routing.querygraph.VirtualEdgeIteratorState
import com.graphhopper.routing.weighting.Weighting
import com.graphhopper.util.EdgeIteratorState
import com.graphhopper.util.GHUtility

/**
 * Wraps GraphHopper's base (fastest) [Weighting] and adds a soft penalty for
 * edges seen by ALPR cameras, in the direction of travel.
 *
 * This is a *native* Weighting — deliberately NOT GraphHopper's JSON custom model,
 * which JIT-compiles via Janino and cannot run on Android (see plan §6 / Phase 3
 * notes). [exposureFwd]/[exposureRev] are per-edge camera counts indexed by edge id
 * (forward = traversing base→adj, reverse = adj→base), built by [CameraExposure].
 *
 * Two subtleties this class handles:
 *
 *  1. **Virtual (snap) edges.** When the start/destination snap onto a road,
 *     GraphHopper's QueryGraph splits that edge into virtual edges with ids ≥
 *     [baseEdgeCount]; during the search they're presented as a `VirtualEdgeIterator`
 *     whose `getEdge()` returns the virtual id. We `detach()` to the underlying
 *     [VirtualEdgeIteratorState] (no allocation — it returns the iterator's current
 *     edge) and resolve the original edge it was carved from, so a camera on the very
 *     first/last edge of a trip is still penalized.
 *
 *  2. **Directional correctness requires unidirectional search.** Because this
 *     weighting is direction-asymmetric (a camera reading one way doesn't penalize the
 *     opposite pass), it MUST be driven by a unidirectional A* — there the `reverse`
 *     flag always matches travel direction. `AStarBidirection`'s backward search
 *     relaxes edges with the reverse flag flipped, which would read the wrong
 *     fwd/rev entry and let directional penalties escape. See [RoutingService.flexRoute].
 *
 * The penalty is additive in the same units as the base weight (≈ seconds), so
 * [penaltyPerCamera] reads as "seconds of detour I'll accept to dodge one camera".
 */
class CameraAvoidanceWeighting(
    private val base: Weighting,
    private val exposureFwd: IntArray,
    private val exposureRev: IntArray,
    private val baseEdgeCount: Int,
    private val penaltyPerCamera: Double,
) : Weighting {

    override fun calcEdgeWeight(edge: EdgeIteratorState, reverse: Boolean): Double {
        val weight = base.calcEdgeWeight(edge, reverse)
        if (weight == Double.POSITIVE_INFINITY) return weight
        return weight + exposureOf(edge, reverse) * penaltyPerCamera
    }

    private fun exposureOf(edge: EdgeIteratorState, reverse: Boolean): Int {
        val id = edge.edge
        if (id < baseEdgeCount) {
            return if (reverse) exposureRev[id] else exposureFwd[id]
        }
        // Virtual (snap) edge — resolve to the original edge it was carved from.
        // detach() is on the public EdgeIteratorState interface; for a virtual-edge
        // iterator it returns the current VirtualEdgeIteratorState (no allocation).
        val state = (edge as? VirtualEdgeIteratorState)
            ?: (edge.detach(false) as? VirtualEdgeIteratorState)
            ?: return 0
        val originalKey = state.originalEdgeKey
        val originalId = GHUtility.getEdgeFromEdgeKey(originalKey)
        if (originalId !in exposureFwd.indices) return 0
        // Even key = original stored (base→adj) orientation aligns with this virtual
        // edge's forward traversal; odd = reversed. Map fwd/rev exposure to match.
        val originalForward = (originalKey and 1) == 0
        val baseToAdj = if (originalForward) exposureFwd[originalId] else exposureRev[originalId]
        val adjToBase = if (originalForward) exposureRev[originalId] else exposureFwd[originalId]
        return if (reverse) adjToBase else baseToAdj
    }

    // The penalty only ever increases weight, so the base remains an admissible
    // lower bound for A*'s heuristic.
    override fun getMinWeight(distance: Double): Double = base.getMinWeight(distance)

    override fun edgeHasNoAccess(edge: EdgeIteratorState, reverse: Boolean): Boolean =
        base.edgeHasNoAccess(edge, reverse)

    override fun calcEdgeMillis(edge: EdgeIteratorState, reverse: Boolean): Long =
        base.calcEdgeMillis(edge, reverse)

    override fun calcTurnWeight(inEdge: Int, viaNode: Int, outEdge: Int): Double =
        base.calcTurnWeight(inEdge, viaNode, outEdge)

    override fun calcTurnMillis(inEdge: Int, viaNode: Int, outEdge: Int): Long =
        base.calcTurnMillis(inEdge, viaNode, outEdge)

    override fun hasTurnCosts(): Boolean = base.hasTurnCosts()

    override fun getName(): String = "ghostroute_camera_avoid"
}

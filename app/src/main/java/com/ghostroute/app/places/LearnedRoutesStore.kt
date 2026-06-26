package com.ghostroute.app.places

import android.content.Context
import com.ghostroute.app.routing.RoutePoint
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject

/**
 * Remembers the path the user habitually drives to a destination, so GhostRoute can default
 * to "your usual" route instead of its own suggestion.
 *
 * 100% on-device (SharedPreferences JSON); never synced or sent anywhere — consistent with
 * the app's privacy rules. Keyed by a rounded destination; stores the LAST driven chain
 * (start … destination) for that place, so the most recent way you went wins.
 */
class LearnedRoutesStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("ghostroute_learned", Context.MODE_PRIVATE)

    /** Record the waypoint [chain] (start … destination) the user just drove to [dest]. */
    fun learn(dest: RoutePoint, chain: List<RoutePoint>) {
        if (chain.size < 2) return
        prefs.edit().putString(key(dest), encode(chain)).apply()
    }

    /**
     * The learned chain for [dest], rebased to begin at [from] — but ONLY when [from] is near
     * where the route was originally learned (so it kicks in for "my usual trip from home",
     * not an unrelated starting point). Returns `[from] + saved waypoints` (destination last),
     * or null when nothing is learned for this place or the start doesn't match.
     */
    fun chainFrom(dest: RoutePoint, from: RoutePoint): List<RoutePoint>? {
        val saved = prefs.getString(key(dest), null)?.let(::decode) ?: return null
        if (saved.size < 2) return null
        if (meters(from, saved.first()) > START_NEAR_M) return null
        return listOf(from) + saved.drop(1)
    }

    /** Forget the learned route for [dest] (e.g. if the user wants to reset it). */
    fun forget(dest: RoutePoint) = prefs.edit().remove(key(dest)).apply()

    // ~110 m destination bucket so the same place matches trip to trip.
    private fun key(p: RoutePoint) = String.format(Locale.US, "dest_%.3f_%.3f", p.lat, p.lon)

    private fun encode(chain: List<RoutePoint>): String {
        val arr = JSONArray()
        chain.forEach { arr.put(JSONObject().put("lat", it.lat).put("lon", it.lon)) }
        return arr.toString()
    }

    private fun decode(raw: String): List<RoutePoint> = try {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RoutePoint(o.getDouble("lat"), o.getDouble("lon"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    companion object {
        /** A learned route only applies if you start within this distance of where it began. */
        private const val START_NEAR_M = 2_000.0
        private const val M_PER_DEG_LAT = 111_320.0
        private const val MAX_WAYPOINTS = 24

        /**
         * Compresses a raw GPS [trace] into a sparse waypoint chain (~[spacingM] apart) that's
         * enough to pin the corridor the driver chose, ending exactly at [dest]. Routing
         * through these waypoints later reconstructs the user's roads with real turn-by-turn.
         */
        fun downsample(trace: List<RoutePoint>, dest: RoutePoint, spacingM: Double = 1_500.0): List<RoutePoint> {
            if (trace.isEmpty()) return listOf(dest)
            val out = ArrayList<RoutePoint>()
            out.add(trace.first())
            var acc = 0.0
            for (i in 1 until trace.size) {
                acc += meters(trace[i - 1], trace[i])
                if (acc >= spacingM) {
                    out.add(trace[i])
                    acc = 0.0
                }
            }
            // End exactly at the real destination, not the last (noisy) fix near it.
            if (meters(out.last(), dest) > 30.0) out.add(dest) else out[out.size - 1] = dest
            // Bound the leg count: keep the first, last, and an even sampling between.
            if (out.size > MAX_WAYPOINTS) {
                val step = (out.size - 1).toDouble() / (MAX_WAYPOINTS - 1)
                val trimmed = (0 until MAX_WAYPOINTS).map { out[(it * step).toInt()] }
                return trimmed.toMutableList().also { it[it.size - 1] = dest }
            }
            return out
        }

        fun meters(a: RoutePoint, b: RoutePoint): Double {
            val mPerDegLon = M_PER_DEG_LAT * cos(Math.toRadians(a.lat))
            val dx = (b.lon - a.lon) * mPerDegLon
            val dy = (b.lat - a.lat) * M_PER_DEG_LAT
            return sqrt(dx * dx + dy * dy)
        }
    }
}

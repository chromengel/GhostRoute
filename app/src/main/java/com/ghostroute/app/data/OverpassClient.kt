package com.ghostroute.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Fetches ALPR/surveillance camera nodes from the OpenStreetMap Overpass API.
 *
 * Uses the platform [HttpURLConnection] + [org.json] (both in the Android SDK) so
 * GhostRoute pulls in no HTTP/JSON third-party libraries — and definitely no GMS.
 * This is the same OSM data source DeFlock/FlockHopper use; data is ODbL.
 */
class OverpassClient(
    private val endpoints: List<String> = DEFAULT_ENDPOINTS,
) {

    /**
     * Queries surveillance cameras tagged as ALPR within [bounds].
     *
     * @return parsed cameras stamped with [syncedAt]; throws on network/parse error
     *   so callers can surface the failure honestly.
     */
    suspend fun fetchAlprCameras(
        bounds: GeoBounds,
        syncedAt: Long,
    ): List<CameraEntity> = withContext(Dispatchers.IO) {
        val body = "data=" + URLEncoder.encode(buildQuery(bounds), "UTF-8")

        // Try the mirrors in order and fail over to the next only if one errors. SEQUENTIAL
        // on purpose: firing the same heavy query at all four public servers at once gets the
        // whole batch rate-limited (429/504) and they fail together. A responsive mirror
        // answers in a few seconds; a hung one drops out at the (short) read timeout below,
        // so a busy primary no longer stalls the whole sync the way the old 180 s wait did.
        var lastError: Exception? = null
        for (endpoint in endpoints) {
            try {
                return@withContext requestOnce(endpoint, body, syncedAt)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: java.io.IOException("No Overpass endpoint reachable")
    }

    private fun requestOnce(endpoint: String, body: String, syncedAt: Long): List<CameraEntity> {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 50_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray()) }
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val err = connection.errorStream?.bufferedReader()?.use(BufferedReader::readText)
                throw java.io.IOException("Overpass HTTP $code${err?.let { ": ${it.take(120)}" } ?: ""}")
            }
            return connection.inputStream.bufferedReader().use(BufferedReader::readText)
                .let { parseElements(it, syncedAt) }
        } finally {
            connection.disconnect()
        }
    }

    private fun buildQuery(b: GeoBounds): String {
        // bbox order for Overpass is (south, west, north, east).
        val bbox = "${b.minLat},${b.minLon},${b.maxLat},${b.maxLon}"
        // One block pulls every surveillance type + speed/red-light cameras. `meta` adds the
        // node version/timestamp (we use the timestamp for per-camera data-freshness); `qt`
        // sorts for speed. The $bbox is substituted per request (dynamic, around the user).
        return """
            [out:json][timeout:60];
            (
              node["man_made"="surveillance"]["surveillance:type"~"ALPR",i]($bbox);
              node["man_made"="surveillance"]["surveillance:type"="gunshot_detector"]($bbox);
              node["man_made"="surveillance"]["surveillance:type"="camera"]($bbox);
              node["man_made"="surveillance"][!"surveillance:type"]($bbox);
              node["highway"="speed_camera"]($bbox);
            );
            out body qt meta;
        """.trimIndent()
    }

    private fun parseElements(json: String, syncedAt: Long): List<CameraEntity> {
        val elements = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        val seen = HashMap<String, CameraEntity>(elements.length())
        for (i in 0 until elements.length()) {
            val el = elements.optJSONObject(i) ?: continue
            if (el.optString("type") != "node") continue
            if (!el.has("lat") || !el.has("lon")) continue
            val id = "node/" + el.optLong("id")
            val tags = el.optJSONObject("tags") ?: JSONObject()
            seen[id] = CameraEntity(
                id = id,
                lat = el.getDouble("lat"),
                lon = el.getDouble("lon"),
                // Mappers use either tag for the bearing — accept both.
                direction = parseDirection(
                    tags.optString("camera:direction").ifBlank { tags.optString("direction") },
                ),
                type = classifyType(tags),
                operator = tags.optString("operator").ifBlank { tags.optString("manufacturer").ifBlank { null } },
                source = CameraEntity.SOURCE_OSM,
                lastSynced = syncedAt,
                osmTimestamp = parseOsmTimestamp(el.optString("timestamp")),
            )
        }
        return seen.values.toList()
    }

    /**
     * Classifies a node by its tags. `highway=speed_camera` uses a separate OSM scheme (no
     * surveillance:type), so branch on that first. Then read surveillance:type; if it's
     * missing we keep the node as a low-confidence [CameraEntity.TYPE_OTHER] pin rather than
     * dropping it — UNLESS the manufacturer/operator is Flock, which always means an ALPR.
     */
    private fun classifyType(tags: JSONObject): String {
        if (tags.optString("highway").equals("speed_camera", ignoreCase = true)) {
            return CameraEntity.TYPE_SPEED
        }
        val surv = tags.optString("surveillance:type").trim()
        val isFlock = tags.optString("manufacturer").contains("Flock", ignoreCase = true) ||
            tags.optString("operator").contains("Flock", ignoreCase = true)
        return when {
            surv.contains("ALPR", ignoreCase = true) -> CameraEntity.TYPE_ALPR
            surv.equals("gunshot_detector", ignoreCase = true) ||
                surv.equals("gunshot", ignoreCase = true) -> CameraEntity.TYPE_GUNSHOT
            surv.equals("camera", ignoreCase = true) -> CameraEntity.TYPE_CAMERA
            surv.equals("dome", ignoreCase = true) -> CameraEntity.TYPE_DOME
            isFlock -> CameraEntity.TYPE_ALPR // Flock-made/operated but untyped → still an ALPR
            else -> CameraEntity.TYPE_OTHER   // catch-all / no surveillance:type → low-confidence
        }
    }

    companion object {
        /** Tried in order; first to answer wins. Mirrors cover for the busy primary. */
        val DEFAULT_ENDPOINTS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        )
        private const val USER_AGENT = "GhostRoute/0.1 (offline ALPR-aware navigation; OSM ODbL)"

        /**
         * Parses an OSM `direction`/`camera:direction` value into degrees [0,360).
         * Accepts a number (e.g. "215") or a compass point (e.g. "NE", "SSW").
         * Returns null for blank/unrecognized values (treated as omnidirectional).
         */
        fun parseDirection(raw: String?): Double? {
            val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            value.toDoubleOrNull()?.let { return ((it % 360) + 360) % 360 }
            return COMPASS_POINTS[value.uppercase()]
        }

        private val COMPASS_POINTS = mapOf(
            "N" to 0.0, "NNE" to 22.5, "NE" to 45.0, "ENE" to 67.5,
            "E" to 90.0, "ESE" to 112.5, "SE" to 135.0, "SSE" to 157.5,
            "S" to 180.0, "SSW" to 202.5, "SW" to 225.0, "WSW" to 247.5,
            "W" to 270.0, "WNW" to 292.5, "NW" to 315.0, "NNW" to 337.5,
        )

        /** Parses an OSM meta `timestamp` (ISO-8601, e.g. "2023-05-14T12:34:56Z") to epoch
         *  millis; null if absent/unparseable. java.time.Instant is fine on minSdk 26. */
        fun parseOsmTimestamp(raw: String?): Long? {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty()) return null
            return try {
                java.time.Instant.parse(s).toEpochMilli()
            } catch (e: Exception) {
                null
            }
        }
    }
}

package com.ghostroute.app.geocode

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A single geocoder hit: a named place/street/POI with a location. */
data class GeoResult(
    val name: String,
    val kind: String,
    /** Short address/locality subtitle to disambiguate same-named hits (may be empty). */
    val addr: String,
    val lat: Double,
    val lon: Double,
)

/**
 * Offline geocoder: full-text search over an on-device SQLite/FTS5 index built from
 * the OSM extract by `scripts/build-geocoder.py`. Entirely local — typing a
 * destination never touches the network or any Google service.
 *
 * The DB is shipped to app storage like the routing graph (too large for the APK)
 * at files/geocoder/geocoder.db.
 */
object GeocoderService {

    private const val TAG = "GhostRouteGeocode"
    private const val DB_NAME = "geocoder.db"

    @Volatile
    private var db: SQLiteDatabase? = null

    fun dbFile(context: Context): File =
        File(File(context.filesDir, "geocoder"), DB_NAME)

    fun isInstalled(context: Context): Boolean =
        dbFile(context).let { it.exists() && it.length() > 0 }

    private fun database(context: Context): SQLiteDatabase? {
        db?.let { return it }
        return synchronized(this) {
            db ?: runCatching {
                SQLiteDatabase.openDatabase(
                    dbFile(context).absolutePath, null, SQLiteDatabase.OPEN_READONLY,
                )
            }.onFailure { Log.e(TAG, "Failed to open geocoder DB", it) }
                .getOrNull()?.also { db = it }
        }
    }

    /**
     * Searches the index for [query], ranked by prominence (cities before streets)
     * and then proximity to [nearLat]/[nearLon] (closer first). Prefix-matches the
     * last token so it works as you type ("knoxv" → Knoxville).
     */
    suspend fun search(
        context: Context,
        query: String,
        nearLat: Double,
        nearLon: Double,
        limit: Int = 25,
    ): List<GeoResult> = withContext(Dispatchers.Default) {
        val q = query.trim()
        if (q.length < 2) return@withContext emptyList()
        val database = database(context) ?: return@withContext emptyList()

        // Tokenize to word characters. The FTS index covers name + address + state
        // (full name and abbreviation) AND house-numbered address points, so
        // "1739 Brevard Road Hendersonville", "Hendersonville NC", and "Main St
        // Hendersonville" all match.
        val tokens = q.split(Regex("\\s+"))
            .map { it.replace(Regex("[^\\p{L}\\p{N}]"), "") }
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return@withContext emptyList()

        // Attempt ladder, most specific first (dedup'd):
        //  1. the full query verbatim — hits a mapped house-number address point if one
        //     exists for that house;
        //  2. street-first: drop a leading house number (rarely mapped), THEN progressively
        //     drop trailing qualifiers until only the distinctive street words remain. The
        //     trailing words we shed are the ones a street record often doesn't carry — an
        //     appended city/state ("…Knoxville, TN") or an abbreviated street type ("Pike"
        //     vs "Pk"). This is what makes "1200 Kingston Pk, Knoxville" land on Kingston
        //     Pike even though neither the house number nor "Knoxville" is in the index.
        val hasHouse = tokens.size > 1 && tokens.first().all { it.isDigit() }
        val attempts = LinkedHashSet<List<String>>()
        // House-inclusive ladder: the full query, then shed trailing qualifiers (street
        // type, appended city) while KEEPING the house number. This pinpoints a mapped
        // house even when the street type is abbreviated — "1200 Kingston Pk" fails on "Pk"
        // but "1200 Kingston" prefix-matches "1200 Kingston Pike".
        run {
            var t = tokens
            while (t.isNotEmpty()) {
                attempts.add(t)
                if (t.size == 1) break
                t = t.dropLast(1)
            }
        }
        // Street-only ladder: drop the leading house number, then shed trailing qualifiers
        // — lands on the street when the exact house isn't mapped.
        if (hasHouse) {
            var base = tokens.drop(1)
            while (base.isNotEmpty()) {
                attempts.add(base)
                if (base.size == 1) break
                base = base.dropLast(1)
            }
        }

        // The distinctive word the user typed (first non-numeric token). Results whose
        // NAME contains it are surfaced ahead of ones that only matched on address/region,
        // so "Kroger" lists Krogers — not a Burger King that sits on "Kroger Park Drive".
        val nameToken = tokens.firstOrNull { tok -> tok.any { !it.isDigit() } }?.lowercase() ?: ""
        val nameLike = "%$nameToken%"

        for (attempt in attempts) {
            // Skip a bare number on its own ("511*") — it matches unrelated noise.
            if (attempt.all { token -> token.all { it.isDigit() } }) continue
            val match = attempt.joinToString(" ") { "$it*" }
            val results = runQuery(database, match, nameLike, nearLat, nearLon, limit)
            if (results.isNotEmpty()) return@withContext results
        }
        emptyList()
    }

    private fun runQuery(
        database: SQLiteDatabase,
        match: String,
        nameLike: String,
        nearLat: Double,
        nearLon: Double,
        limit: Int,
    ): List<GeoResult> {
        // Ordering, in priority order:
        //  1. NAME matches first — a hit whose name contains the typed word beats one that
        //     only matched via its address (so "Kroger" → actual Krogers, not a café on a
        //     "Kroger" street).
        //  2. Then nearest-first, lightly tempered by prominence: every POI shares one flat
        //     rank (25, above streets 12 / addresses 10), so within a tier we sort by a
        //     blend (rank − 1.5 × squared-degree distance). That keeps the closest match on
        //     top while still letting a genuinely prominent far city (rank 100) win when its
        //     name is typed.
        //  3. Pure distance as the final tiebreak.
        val sql =
            "SELECT p.name, p.kind, p.addr, p.lat, p.lon FROM places_fts f " +
                "JOIN places p ON p.id = f.docid " +
                "WHERE places_fts MATCH ? " +
                "ORDER BY (CASE WHEN LOWER(p.name) LIKE ? THEN 0 ELSE 1 END) ASC, " +
                "(p.rank - 1.5 * ((p.lat - ?) * (p.lat - ?) + (p.lon - ?) * (p.lon - ?))) DESC, " +
                "((p.lat - ?) * (p.lat - ?) + (p.lon - ?) * (p.lon - ?)) ASC " +
                "LIMIT ?"
        val lat = nearLat.toString()
        val lon = nearLon.toString()
        val args = arrayOf(
            match,
            nameLike, // name-match tier
            lat, lat, lon, lon, // distance penalty in the score
            lat, lat, lon, lon, // distance tiebreak
            limit.toString(),
        )
        return runCatching {
            database.rawQuery(sql, args).use { c ->
                val out = ArrayList<GeoResult>(c.count)
                while (c.moveToNext()) {
                    out.add(
                        GeoResult(
                            name = c.getString(0),
                            kind = c.getString(1),
                            addr = c.getString(2) ?: "",
                            lat = c.getDouble(3),
                            lon = c.getDouble(4),
                        ),
                    )
                }
                out
            }
        }.onFailure { Log.w(TAG, "Geocoder query failed", it) }.getOrDefault(emptyList())
    }
}

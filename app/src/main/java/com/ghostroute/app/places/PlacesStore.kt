package com.ghostroute.app.places

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** A place the user saved (a recent, a favorite, or Home/Work). */
data class SavedPlace(
    val name: String,
    val addr: String,
    val lat: Double,
    val lon: Double,
)

/**
 * Local, offline store for the user's recent destinations and favorites (Home, Work, and
 * any pinned places). Backed by SharedPreferences as small JSON blobs — no network, no
 * Google account, nothing leaves the device. Survives app restarts.
 */
class PlacesStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun recents(): List<SavedPlace> = readList(KEY_RECENTS)
    fun favorites(): List<SavedPlace> = readList(KEY_FAVORITES)
    fun home(): SavedPlace? = readOne(KEY_HOME)
    fun work(): SavedPlace? = readOne(KEY_WORK)

    /** Add (or move to top) a recent destination, capped + de-duplicated by location. */
    fun addRecent(place: SavedPlace) {
        val list = recents().filterNot { sameSpot(it, place) }.toMutableList()
        list.add(0, place)
        writeList(KEY_RECENTS, list.take(MAX_RECENTS))
    }

    fun setHome(place: SavedPlace) = writeOne(KEY_HOME, place)
    fun setWork(place: SavedPlace) = writeOne(KEY_WORK, place)

    fun clearHome() = prefs.edit().remove(KEY_HOME).apply()
    fun clearWork() = prefs.edit().remove(KEY_WORK).apply()

    fun addFavorite(place: SavedPlace) {
        val list = favorites().filterNot { sameSpot(it, place) }.toMutableList()
        list.add(0, place)
        writeList(KEY_FAVORITES, list.take(MAX_FAVORITES))
    }

    fun removeFavorite(place: SavedPlace) {
        writeList(KEY_FAVORITES, favorites().filterNot { sameSpot(it, place) })
    }

    /** Rename the favorite at [place]'s location (keeps its address + coordinates). */
    fun renameFavorite(place: SavedPlace, newName: String) {
        val name = newName.trim().ifEmpty { return }
        writeList(KEY_FAVORITES, favorites().map { if (sameSpot(it, place)) it.copy(name = name) else it })
    }

    // ---- JSON (de)serialization ----

    private fun sameSpot(a: SavedPlace, b: SavedPlace): Boolean =
        abs(a.lat - b.lat) < 1e-4 && abs(a.lon - b.lon) < 1e-4

    private fun readList(key: String): List<SavedPlace> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { fromJson(arr.optJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeList(key: String, list: List<SavedPlace>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    private fun readOne(key: String): SavedPlace? {
        val raw = prefs.getString(key, null) ?: return null
        return try {
            fromJson(JSONObject(raw))
        } catch (e: Exception) {
            null
        }
    }

    private fun writeOne(key: String, place: SavedPlace) {
        prefs.edit().putString(key, toJson(place).toString()).apply()
    }

    private fun toJson(p: SavedPlace): JSONObject =
        JSONObject()
            .put("name", p.name)
            .put("addr", p.addr)
            .put("lat", p.lat)
            .put("lon", p.lon)

    private fun fromJson(o: JSONObject?): SavedPlace? {
        o ?: return null
        if (!o.has("lat") || !o.has("lon")) return null
        return SavedPlace(
            name = o.optString("name"),
            addr = o.optString("addr"),
            lat = o.optDouble("lat"),
            lon = o.optDouble("lon"),
        )
    }

    private companion object {
        const val PREFS = "ghostroute_places"
        const val KEY_RECENTS = "recents"
        const val KEY_FAVORITES = "favorites"
        const val KEY_HOME = "home"
        const val KEY_WORK = "work"
        const val MAX_RECENTS = 12
        const val MAX_FAVORITES = 12
    }
}

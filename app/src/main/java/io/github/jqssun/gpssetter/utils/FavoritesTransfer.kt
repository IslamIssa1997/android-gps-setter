package io.github.jqssun.gpssetter.utils

import android.content.Context
import android.net.Uri
import io.github.jqssun.gpssetter.room.Favorite
import io.github.jqssun.gpssetter.room.FavoriteDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Backs saved locations up to a JSON file and restores them again.
 *
 * The format is deliberately plain so it can be edited by hand or moved between installs -- which
 * matters here, because changing the app's package name makes Android treat it as a different app
 * and its data does not carry over.
 */
object FavoritesTransfer {

    private const val FORMAT_VERSION = 1

    /** @return how many favourites were written. */
    suspend fun export(context: Context, dao: FavoriteDao, target: Uri): Int =
        withContext(Dispatchers.IO) {
            val favorites = dao.getAllFavorites().first()
            val array = JSONArray()
            favorites.forEach { favorite ->
                array.put(
                    JSONObject().apply {
                        put("address", favorite.address ?: "")
                        put("lat", favorite.lat ?: 0.0)
                        put("lng", favorite.lng ?: 0.0)
                        favorite.description?.let { put("description", it) }
                    }
                )
            }
            val document = JSONObject().apply {
                put("version", FORMAT_VERSION)
                put("favorites", array)
            }
            context.contentResolver.openOutputStream(target)?.use { stream ->
                stream.write(document.toString(2).toByteArray())
            } ?: error("could not open $target for writing")
            Timber.i("exported %d favourites", favorites.size)
            favorites.size
        }

    /**
     * @return how many favourites were added. Entries whose coordinates already exist are skipped,
     *   so importing the same file twice does not duplicate everything.
     */
    suspend fun import(context: Context, dao: FavoriteDao, source: Uri): Int =
        withContext(Dispatchers.IO) {
            val text = context.contentResolver.openInputStream(source)?.use {
                it.readBytes().decodeToString()
            } ?: error("could not open $source for reading")

            val root = JSONObject(text)
            val array = root.optJSONArray("favorites") ?: JSONArray()
            val existing = dao.getAllFavorites().first()
                .map { it.lat to it.lng }
                .toSet()

            var added = 0
            for (i in 0 until array.length()) {
                val entry = array.optJSONObject(i) ?: continue
                val lat = entry.optDouble("lat")
                val lng = entry.optDouble("lng")
                if (lat.isNaN() || lng.isNaN()) continue
                if ((lat to lng) in existing) continue

                dao.insertToRoomDatabase(
                    Favorite(
                        // Room's primary key is not auto-generated here, so an id has to be
                        // supplied; the clock plus the index keeps them unique within an import.
                        id = System.currentTimeMillis() + i,
                        address = entry.optString("address").takeIf { it.isNotEmpty() },
                        lat = lat,
                        lng = lng,
                        description = entry.optString("description").takeIf { it.isNotEmpty() },
                    )
                )
                added++
            }
            Timber.i("imported %d of %d favourites", added, array.length())
            added
        }
}

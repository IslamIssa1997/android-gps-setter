package io.github.jqssun.gpssetter.utils

import io.github.jqssun.gpssetter.BuildConfig
import io.github.jqssun.gpssetter.ui.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Place / POI search from two sources, biased to the area the map is currently showing:
 * Google Places (priority, when a key is set) and OpenStreetMap (Nominatim, free). Results are
 * returned Google-first so the more accurate matches lead. Does not change the map.
 */
object PlacesService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /**
     * Runs every available source at once (OpenStreetMap always, Google when a key is set) and
     * merges the results, dropping near-duplicates. @param bounds [swLat, swLon, neLat, neLon].
     */
    suspend fun searchAll(query: String, bounds: DoubleArray?): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val google = async { googlePlaces(query, bounds) }
            val osm = async { nominatim(query, bounds) }
            // Google leads (priority); OpenStreetMap follows. Near-duplicates are dropped by the
            // caller, which keeps the first occurrence -- i.e. the Google one.
            google.await() + osm.await()
        }


    private suspend fun nominatim(query: String, bounds: DoubleArray?): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val q = URLEncoder.encode(query, "UTF-8")
            // bounded=1 keeps Nominatim results strictly inside the current view.
            val viewbox = bounds?.let {
                "&viewbox=${it[1]},${it[0]},${it[3]},${it[2]}&bounded=1"
            } ?: ""
            val url = "https://nominatim.openstreetmap.org/search" +
                "?q=$q&format=json&limit=10&accept-language=ar,en$viewbox"
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "GPSSetter/${BuildConfig.TAG_NAME} (personal use)")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyList()
                    val body = response.body?.string() ?: return@withContext emptyList()
                    val arr = org.json.JSONArray(body)
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        val lat = o.optString("lat").toDoubleOrNull() ?: return@mapNotNull null
                        val lon = o.optString("lon").toDoubleOrNull() ?: return@mapNotNull null
                        SearchResult(lat, lon, o.optString("display_name", "$lat, $lon"))
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Nominatim search error")
                emptyList()
            }
        }

    private suspend fun googlePlaces(query: String, bounds: DoubleArray?): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val key = BuildConfig.PLACES_API_KEY
            if (key.isBlank()) {
                Timber.i("Google Places skipped: no key")
                return@withContext emptyList()
            }
            val q = URLEncoder.encode(query, "UTF-8")
            val loc = bounds?.let {
                "&location=${(it[0] + it[2]) / 2},${(it[1] + it[3]) / 2}&radius=50000"
            } ?: ""
            val url = "https://maps.googleapis.com/maps/api/place/textsearch/json" +
                "?query=$q&language=ar$loc&key=$key"
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyList()
                    val body = response.body?.string() ?: return@withContext emptyList()
                    val root = JSONObject(body)
                    val status = root.optString("status")
                    if (status != "OK") {
                        Timber.w("Google Places status: %s (%s)", status, root.optString("error_message"))
                        return@withContext emptyList()
                    }
                    val results = root.optJSONArray("results") ?: return@withContext emptyList()
                    (0 until results.length()).mapNotNull { i ->
                        val o = results.optJSONObject(i) ?: return@mapNotNull null
                        val loc2 = o.optJSONObject("geometry")?.optJSONObject("location")
                            ?: return@mapNotNull null
                        val lat = loc2.optDouble("lat", Double.NaN)
                        val lon = loc2.optDouble("lng", Double.NaN)
                        if (lat.isNaN() || lon.isNaN()) return@mapNotNull null
                        val name = o.optString("name")
                        val addr = o.optString("formatted_address")
                        SearchResult(lat, lon, if (addr.isNotBlank()) "$name — $addr" else name)
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Google Places search error")
                emptyList()
            }
        }
}

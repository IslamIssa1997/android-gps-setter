package io.github.jqssun.gpssetter.utils

import com.google.openlocationcode.OpenLocationCode
import timber.log.Timber

/**
 * Turns a Plus Code / Open Location Code typed into the search box into coordinates
 * (e.g. `8FVC9G8F+6X`, or a short `9G8F+6X` completed against the current map centre).
 *
 * Returns null when the input is not a Plus Code, so the caller falls through to normal search.
 */
object CodeResolver {

    data class Coord(val lat: Double, val lon: Double)



    /**
     * @param refLat/refLon the centre of the current map view, used to expand a short Plus Code.
     */
    suspend fun resolve(input: String, refLat: Double?, refLon: Double?): Coord? {
        val text = input.trim()
        return plusCode(text, refLat, refLon)
    }

    // ---- Plus Codes (offline) --------------------------------------------

    private fun plusCode(input: String, refLat: Double?, refLon: Double?): Coord? {
        // Pull out the token that carries the '+', ignoring any trailing locality text.
        val token = input.split(" ").firstOrNull { it.contains("+") } ?: return null
        if (!OpenLocationCode.isValidCode(token)) return null
        return runCatching {
            val full = if (OpenLocationCode.isFull(token)) {
                OpenLocationCode(token)
            } else {
                // A short code needs a nearby reference point to be completed.
                if (refLat == null || refLon == null) return null
                OpenLocationCode(token).recover(refLat, refLon)
            }
            val area = full.decode()
            Coord(area.centerLatitude, area.centerLongitude)
        }.onFailure { Timber.w(it, "plus code decode failed for %s", input) }.getOrNull()
    }
}

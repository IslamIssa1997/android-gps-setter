package io.github.jqssun.gpssetter.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Looks up real-world height information for a coordinate.
 *
 * "Auto" altitude has to be the actual terrain height, not an invented number: a device claiming
 * to be on a 2000 m mountain while reporting 25 m is trivially inconsistent, and so is the reverse.
 *
 * Open-Meteo is used rather than Google's Elevation API for two reasons: it needs no key and no
 * billing, and the project's Google key is restricted to *Android app + signing certificate*,
 * which the Elevation API rejects outright since it is a web service rather than an SDK.
 */
object ElevationService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    /**
     * Ground height above sea level, in metres, which maps directly onto
     * `Location.getMslAltitudeMeters()`.
     *
     * @return null if the lookup failed. Callers must treat that as "no data" and fall back
     *   rather than substituting a made-up value.
     */
    suspend fun lookup(latitude: Double, longitude: Double): Float? = withContext(Dispatchers.IO) {
        val url = "https://api.open-meteo.com/v1/elevation" +
            "?latitude=$latitude&longitude=$longitude"
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("elevation lookup failed: HTTP %d", response.code)
                    return@withContext null
                }
                val body = response.body?.string() ?: return@withContext null
                val array = JSONObject(body).optJSONArray("elevation") ?: return@withContext null
                if (array.length() == 0) return@withContext null
                array.getDouble(0).toFloat().also {
                    Timber.i("elevation for %f,%f = %.1f m", latitude, longitude, it)
                }
            }
        } catch (e: Exception) {
            // Offline, DNS failure, timeout -- all just mean "no elevation data this time".
            Timber.w(e, "elevation lookup error")
            null
        }
    }

    /**
     * The local gap between "height above sea level" and "height above the smooth Earth model".
     *
     * The sea does not sit a constant distance from that model -- gravity makes it bulge and sag
     * by several metres depending on where you are (about +5 m around Jeddah). Without this, the
     * two height figures a phone reports would not line up the way they do on real hardware.
     *
     * @return the gap in metres, or null if it could not be determined.
     */
    suspend fun lookupGeoidOffset(latitude: Double, longitude: Double): Float? =
        withContext(Dispatchers.IO) {
            val url = "https://geographiclib.sourceforge.io/cgi-bin/GeoidEval" +
                "?input=$latitude+$longitude"
            try {
                Timber.i("geoid lookup starting for %f,%f", latitude, longitude)
                // This service is noticeably slower than the elevation one, so it gets its own
                // longer timeout rather than failing on a cold connection.
                val slowClient = client.newBuilder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()
                // The service rejects requests carrying okhttp's user agent, and a browser one
                // too, but accepts an empty one. That is clearly not a documented contract, so
                // treat this whole lookup as best-effort: any failure falls back to the built-in
                // estimate rather than breaking altitude.
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "")
                    .build()
                slowClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Timber.w("geoid lookup failed: HTTP %d", response.code)
                        return@withContext null
                    }
                    val body = response.body?.string() ?: return@withContext null
                    // The service answers with a web page, not structured data, and the figure
                    // sits between markup: <a ...>EGM2008</a> = <font ...>4.9718</font>. Tags are
                    // stripped first so the pattern does not depend on that markup staying put.
                    // If the page ever changes shape this returns null and the caller keeps the
                    // value it already had.
                    val plain = body.replace(Regex("<[^>]*>"), "")
                    val match = Regex("""EGM2008\s*=\s*(-?[0-9.]+)""").find(plain)
                        ?: Regex("""EGM96\s*=\s*(-?[0-9.]+)""").find(plain)
                    val value = match?.groupValues?.get(1)?.toFloatOrNull()
                    if (value == null) Timber.w("geoid response did not contain a value")
                    else Timber.i("geoid offset for %f,%f = %.2f m", latitude, longitude, value)
                    value
                }
            } catch (e: Exception) {
                Timber.w(e, "geoid lookup error")
                null
            }
        }
}

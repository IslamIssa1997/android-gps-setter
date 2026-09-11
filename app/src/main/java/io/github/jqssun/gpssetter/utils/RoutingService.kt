package io.github.jqssun.gpssetter.utils

import io.github.jqssun.gpssetter.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.net.URLEncoder

data class RoutingWaypoint(
    val latitude: Double,
    val longitude: Double
)

data class RouteResponse(
    val waypoints: List<RoutingWaypoint>,
    val distance: Double, // in meters
    val duration: Double  // in seconds
)

class RoutingService {
    private val client = OkHttpClient()

    // Using OpenRouteService API (free tier: 2000 requests/day)
    // Alternative: you can also use MapBox, Google Directions, or OSRM
    private val baseUrl = "https://api.openrouteservice.org/v2/directions"

    // Supplied via local.properties as ORS_API_KEY (see app/build.gradle). Get a free key at
    // https://openrouteservice.org/dev/#/signup
    private val apiKey = BuildConfig.ORS_API_KEY

    suspend fun getRoute(
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double,
        profile: String = "driving-car" // driving-car, cycling-regular, foot-walking
    ): RouteResponse? {
        return withContext(Dispatchers.IO) {
            try {
                // The GET directions endpoint takes separate start/end params (lon,lat order),
                // NOT a coordinates=... list -- that form returns HTTP 400 ("Parameter 'start' is
                // missing") and used to make every route silently fall back to a straight line.
                val url = "$baseUrl/$profile?" +
                        "api_key=$apiKey&" +
                        "start=$startLon,$startLat&" +
                        "end=$endLon,$endLat"

                Timber.d("Making routing request to: $url")

                val request = Request.Builder()
                    .url(url)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                Timber.d("Routing response: ${response.code}, body length: ${responseBody?.length}")

                if (response.isSuccessful && responseBody != null) {
                    val result = parseRouteResponse(responseBody)
                    Timber.d("Parsed route with ${result?.waypoints?.size ?: 0} waypoints")
                    result
                } else {
                    Timber.e("Routing failed: ${response.code} - $responseBody")
                    // Fallback to straight line if routing fails
                    Timber.d("Using fallback straight line route")
                    createStraightLineRoute(startLat, startLon, endLat, endLon)
                }
            } catch (e: Exception) {
                Timber.e(e, "Error getting route")
                // Fallback to straight line if routing fails
                createStraightLineRoute(startLat, startLon, endLat, endLon)
            }
        }
    }

    /**
     * Routes through a list of points in order (start, intermediate stops..., end).
     *
     * Uses the POST endpoint, which is the only one that accepts more than two points -- the GET
     * form used by [getRoute] takes a single start and end. Falls back to a plain start-to-end
     * route, then to a straight line, if the multi-point request fails.
     */
    suspend fun getRouteVia(
        points: List<RoutingWaypoint>,
        profile: String = "driving-car",
    ): RouteResponse? = withContext(Dispatchers.IO) {
        if (points.size < 2) return@withContext null
        if (points.size == 2) {
            return@withContext getRoute(
                points[0].latitude, points[0].longitude,
                points[1].latitude, points[1].longitude, profile,
            )
        }
        try {
            val coords = JSONArray()
            points.forEach { wp -> coords.put(JSONArray().put(wp.longitude).put(wp.latitude)) }
            val bodyJson = JSONObject().put("coordinates", coords).toString()
            val request = Request.Builder()
                .url("$baseUrl/$profile/geojson")
                .header("Authorization", apiKey)
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            if (response.isSuccessful && responseBody != null) {
                parseRouteResponse(responseBody)
                    ?: getRoute(points.first().latitude, points.first().longitude,
                        points.last().latitude, points.last().longitude, profile)
            } else {
                Timber.e("Multi-point routing failed: ${response.code} - $responseBody")
                getRoute(points.first().latitude, points.first().longitude,
                    points.last().latitude, points.last().longitude, profile)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error getting multi-point route")
            getRoute(points.first().latitude, points.first().longitude,
                points.last().latitude, points.last().longitude, profile)
        }
    }

        private fun parseRouteResponse(json: String): RouteResponse? {
        return try {
            val jsonObject = JSONObject(json)
            val features = jsonObject.getJSONArray("features")

            if (features.length() > 0) {
                val feature = features.getJSONObject(0)
                val geometry = feature.getJSONObject("geometry")
                val coordinates = geometry.getJSONArray("coordinates")
                val properties = feature.getJSONObject("properties")
                val summary = properties.getJSONObject("summary")

                val waypoints = mutableListOf<RoutingWaypoint>()

                for (i in 0 until coordinates.length()) {
                    val coord = coordinates.getJSONArray(i)
                    val lon = coord.getDouble(0)
                    val lat = coord.getDouble(1)
                    waypoints.add(RoutingWaypoint(lat, lon))
                }

                val distance = summary.getDouble("distance")
                val duration = summary.getDouble("duration")

                RouteResponse(waypoints, distance, duration)
            } else {
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "Error parsing route response")
            null
        }
    }

    private fun createStraightLineRoute(
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double
    ): RouteResponse {
        // Create a simple straight line route as fallback
        val waypoints = listOf(
            RoutingWaypoint(startLat, startLon),
            RoutingWaypoint(endLat, endLon)
        )

        // Calculate approximate distance using Haversine formula
        val distance = calculateDistance(startLat, startLon, endLat, endLon)
        val duration = distance / 13.89 // Assume ~50 km/h average speed

        return RouteResponse(waypoints, distance, duration)
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0 // Earth's radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return earthRadius * c
    }

    // Get route profile based on navigation speed
    fun getProfileForSpeed(speedMps: Float): String {
        return when {
            speedMps <= 2.0f -> "foot-walking"      // Walking speed
            speedMps <= 8.0f -> "cycling-regular"   // Cycling speed
            else -> "driving-car"                    // Driving speed
        }
    }
}
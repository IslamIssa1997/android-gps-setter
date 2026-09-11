package io.github.jqssun.gpssetter.utils

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import javax.inject.Inject
import kotlin.math.*

@AndroidEntryPoint
class NavigationService : Service() {

    @Inject
    lateinit var notificationsChannel: NotificationsChannel

    private val routingService = RoutingService()
    private var serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var navigationJob: Job? = null
    private var routeWaypoints: List<RoutingWaypoint> = emptyList()

    private val _currentRoute = MutableStateFlow<NavigationRoute?>(null)
    val currentRoute: StateFlow<NavigationRoute?> = _currentRoute.asStateFlow()

    private val _navigationState = MutableStateFlow(NavigationState.STOPPED)
    val navigationState: StateFlow<NavigationState> = _navigationState.asStateFlow()

    private val _navigationProgress = MutableStateFlow<NavigationProgress?>(null)
    val navigationProgress: StateFlow<NavigationProgress?> = _navigationProgress.asStateFlow()

    private val _currentPosition = MutableStateFlow<RoutePoint?>(null)
    val currentPosition: StateFlow<RoutePoint?> = _currentPosition.asStateFlow()

    private val _currentRouteWaypoints = MutableStateFlow<List<RoutingWaypoint>>(emptyList())
    val currentRouteWaypoints: StateFlow<List<RoutingWaypoint>> = _currentRouteWaypoints.asStateFlow()

    inner class NavigationBinder : Binder() {
        fun getService(): NavigationService = this@NavigationService
    }

    override fun onBind(intent: Intent): IBinder {
        return NavigationBinder()
    }

    override fun onCreate() {
        super.onCreate()
        Timber.d("NavigationService created")
    }

    override fun onDestroy() {
        super.onDestroy()
        PrefManager.routeActive = false
        stopNavigation()
        serviceScope.cancel()
        Timber.d("NavigationService destroyed")
    }

    fun startNavigation(route: NavigationRoute) {
        Timber.d("Starting navigation for route: ${route.name}")

        stopNavigation() // Stop any existing navigation

        _currentRoute.value = route.copy(isActive = true)
        _navigationState.value = NavigationState.RUNNING
        PrefManager.routeActive = true
        PrefManager.routeSpeedMs = route.speed  // report the route's steady speed while it runs

        startForeground(NAVIGATION_NOTIFICATION_ID, createNavigationNotification(route))

        navigationJob = serviceScope.launch {
            // Get real route from routing service
            val profile = routingService.getProfileForSpeed(route.speed)
            // Route through any intermediate stops the user added, in order.
            val allPoints = buildList {
                add(RoutingWaypoint(route.startPoint.latitude, route.startPoint.longitude))
                route.waypoints.forEach { add(RoutingWaypoint(it.latitude, it.longitude)) }
                add(RoutingWaypoint(route.endPoint.latitude, route.endPoint.longitude))
            }
            val routeResponse = if (allPoints.size > 2) {
                routingService.getRouteVia(allPoints, profile)
            } else {
                routingService.getRoute(
                    route.startPoint.latitude, route.startPoint.longitude,
                    route.endPoint.latitude, route.endPoint.longitude, profile,
                )
            }

            if (routeResponse != null) {
                routeWaypoints = routeResponse.waypoints
                _currentRouteWaypoints.value = routeResponse.waypoints
                simulateRouteWithWaypoints(route, routeResponse)
            } else {
                // Fallback to straight line if routing fails
                routeWaypoints = listOf(
                    RoutingWaypoint(route.startPoint.latitude, route.startPoint.longitude),
                    RoutingWaypoint(route.endPoint.latitude, route.endPoint.longitude)
                )
                _currentRouteWaypoints.value = routeWaypoints
                simulateRoute(route)
            }
        }
    }

    fun pauseNavigation() {
        if (_navigationState.value == NavigationState.RUNNING) {
            _navigationState.value = NavigationState.PAUSED
            navigationJob?.cancel()
        }
    }

    fun resumeNavigation() {
        if (_navigationState.value == NavigationState.PAUSED) {
            _currentRoute.value?.let { route ->
                _navigationState.value = NavigationState.RUNNING
                navigationJob = serviceScope.launch {
                    if (routeWaypoints.size > 2) {
                        // Use waypoint-based navigation if we have road route
                        val routeResponse = RouteResponse(
                            waypoints = routeWaypoints,
                            distance = calculateTotalDistance(routeWaypoints),
                            duration = 0.0 // Duration will be calculated based on speed
                        )
                        simulateRouteWithWaypoints(route, routeResponse, _navigationProgress.value?.progressPercentage ?: 0f)
                    } else {
                        // Fall back to straight line navigation
                        simulateRoute(route, _navigationProgress.value?.progressPercentage ?: 0f)
                    }
                }
            }
        }
    }

    fun stopNavigation() {
        navigationJob?.cancel()
        PrefManager.routeActive = false
        PrefManager.routeSpeedMs = null
        _navigationState.value = NavigationState.STOPPED
        _currentRoute.value = null
        _navigationProgress.value = null
        _currentPosition.value = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private suspend fun simulateRouteWithWaypoints(route: NavigationRoute, routeResponse: RouteResponse, startProgress: Float = 0f) {
        val waypoints = routeResponse.waypoints
        if (waypoints.size < 2) {
            // Fallback to simple route if not enough waypoints
            simulateRoute(route, startProgress)
            return
        }

        val totalDistance = routeResponse.distance
        // Tick ~5x/second. Position is driven by REAL elapsed time, not a step counter: each
        // iteration does work (interpolate, emit, pref write) so the true loop period is a bit more
        // than the delay, and a step-counted loop then moves the pin slower than the set speed --
        // which is why apps that derive speed from GPS movement showed 200 km/h as ~160. Time-based
        // progress makes the pin cover the distance in exactly distance/speed seconds regardless.
        val stepDelay = 200L
        val totalTimeMs = (if (route.duration > 0) route.duration
                           else (totalDistance / route.speed * 1000).toLong()).coerceAtLeast(1L)
        var resumeProgress = startProgress

        do {
            val startTime = System.currentTimeMillis() - (resumeProgress * totalTimeMs).toLong()
            while (_navigationState.value == NavigationState.RUNNING) {
                val elapsedTime = System.currentTimeMillis() - startTime
                val progress = (elapsedTime.toFloat() / totalTimeMs).coerceIn(0f, 1f)
                val currentPos = interpolatePositionAlongWaypoints(waypoints, progress)

                _currentPosition.value = currentPos

                val remainingTime = (totalTimeMs - elapsedTime).coerceAtLeast(0)
                _navigationProgress.value = NavigationProgress(
                    currentPosition = currentPos,
                    progressPercentage = progress * 100f,
                    elapsedTime = elapsedTime,
                    remainingTime = remainingTime
                )

                if (progress >= 1f) break
                if (route.duration > 0 && elapsedTime >= route.duration) break

                delay(stepDelay)
            }

            // If repeating and not stopped, start the next lap from the beginning.
            if (route.isRepeating && _navigationState.value == NavigationState.RUNNING) {
                resumeProgress = 0f
                delay(1000) // Small pause before repeating
            }

        } while (route.isRepeating && _navigationState.value == NavigationState.RUNNING)

        // Navigation completed
        if (_navigationState.value == NavigationState.RUNNING) {
            Timber.d("Navigation completed")
            stopNavigation()
        }
    }

    private suspend fun simulateRoute(route: NavigationRoute, startProgress: Float = 0f) {
        val totalDistance = calculateDistance(route.startPoint, route.endPoint)
        // Time-based progress -- see the note in simulateRouteWithWaypoints for why (a step counter
        // undershoots the set speed because per-iteration work makes each tick longer than the delay).
        val stepDelay = 200L
        val totalTimeMs = (if (route.duration > 0) route.duration
                           else (totalDistance / route.speed * 1000).toLong()).coerceAtLeast(1L)
        var resumeProgress = startProgress

        do {
            val startTime = System.currentTimeMillis() - (resumeProgress * totalTimeMs).toLong()
            while (_navigationState.value == NavigationState.RUNNING) {
                val elapsedTime = System.currentTimeMillis() - startTime
                val progress = (elapsedTime.toFloat() / totalTimeMs).coerceIn(0f, 1f)
                val currentPos = interpolatePosition(route.startPoint, route.endPoint, progress)

                _currentPosition.value = currentPos

                val remainingTime = (totalTimeMs - elapsedTime).coerceAtLeast(0)
                _navigationProgress.value = NavigationProgress(
                    currentPosition = currentPos,
                    progressPercentage = progress * 100f,
                    elapsedTime = elapsedTime,
                    remainingTime = remainingTime
                )

                if (progress >= 1f) break
                if (route.duration > 0 && elapsedTime >= route.duration) break

                delay(stepDelay)
            }

            if (route.isRepeating && _navigationState.value == NavigationState.RUNNING) {
                resumeProgress = 0f
                delay(1000) // Small pause before repeating
            }

        } while (route.isRepeating && _navigationState.value == NavigationState.RUNNING)

        // Navigation completed
        if (_navigationState.value == NavigationState.RUNNING) {
            Timber.d("Navigation completed")
            stopNavigation()
        }
    }

    private fun interpolatePositionAlongWaypoints(waypoints: List<RoutingWaypoint>, progress: Float): RoutePoint {
        if (waypoints.size < 2) {
            return RoutePoint(waypoints[0].latitude, waypoints[0].longitude, "Current Position")
        }

        // Calculate total distance along the route
        val segmentDistances = mutableListOf<Double>()
        var totalDistance = 0.0

        for (i in 0 until waypoints.size - 1) {
            val distance = calculateDistance(
                RoutePoint(waypoints[i].latitude, waypoints[i].longitude, ""),
                RoutePoint(waypoints[i + 1].latitude, waypoints[i + 1].longitude, "")
            )
            segmentDistances.add(distance)
            totalDistance += distance
        }

        // Find the target distance along the route
        val targetDistance = totalDistance * progress
        var accumulatedDistance = 0.0

        // Find which segment contains our target position
        for (i in 0 until segmentDistances.size) {
            val segmentDistance = segmentDistances[i]

            if (accumulatedDistance + segmentDistance >= targetDistance) {
                // Our position is in this segment
                val remainingDistance = targetDistance - accumulatedDistance
                val segmentProgress = if (segmentDistance > 0) remainingDistance / segmentDistance else 0.0

                // Interpolate between the two waypoints of this segment
                val start = waypoints[i]
                val end = waypoints[i + 1]

                val lat = start.latitude + (end.latitude - start.latitude) * segmentProgress.toFloat()
                val lon = start.longitude + (end.longitude - start.longitude) * segmentProgress.toFloat()

                return RoutePoint(lat, lon, "Current Position")
            }

            accumulatedDistance += segmentDistance
        }

        // If we somehow get here, return the last waypoint
        val lastWaypoint = waypoints.last()
        return RoutePoint(lastWaypoint.latitude, lastWaypoint.longitude, "Current Position")
    }

    private fun interpolatePosition(start: RoutePoint, end: RoutePoint, progress: Float): RoutePoint {
        val lat = start.latitude + (end.latitude - start.latitude) * progress
        val lon = start.longitude + (end.longitude - start.longitude) * progress
        return RoutePoint(lat, lon, "Current Position")
    }

    private fun calculateDistance(start: RoutePoint, end: RoutePoint): Double {
        val earthRadius = 6371000.0 // Earth's radius in meters

        val lat1Rad = Math.toRadians(start.latitude)
        val lat2Rad = Math.toRadians(end.latitude)
        val deltaLatRad = Math.toRadians(end.latitude - start.latitude)
        val deltaLonRad = Math.toRadians(end.longitude - start.longitude)

        val a = sin(deltaLatRad / 2).pow(2) + cos(lat1Rad) * cos(lat2Rad) * sin(deltaLonRad / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return earthRadius * c
    }

    private fun calculateTotalDistance(waypoints: List<RoutingWaypoint>): Double {
        if (waypoints.size < 2) return 0.0

        var totalDistance = 0.0
        for (i in 0 until waypoints.size - 1) {
            val start = RoutePoint(waypoints[i].latitude, waypoints[i].longitude, "")
            val end = RoutePoint(waypoints[i + 1].latitude, waypoints[i + 1].longitude, "")
            totalDistance += calculateDistance(start, end)
        }
        return totalDistance
    }

    private fun createNavigationNotification(route: NavigationRoute): Notification {
        return notificationsChannel.showNotification(this) { builder ->
            builder.setContentTitle("Auto Navigation Active")
                .setContentText("Route: ${route.name}")
                .setSmallIcon(R.drawable.ic_play)
                .setOngoing(true)
        }
    }

    companion object {
        private const val NAVIGATION_NOTIFICATION_ID = 1001
    }
}
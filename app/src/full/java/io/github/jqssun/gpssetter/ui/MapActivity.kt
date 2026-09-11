package io.github.jqssun.gpssetter.ui


import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.utils.ext.getAddress
import io.github.jqssun.gpssetter.utils.ext.showToast
import kotlinx.coroutines.launch

typealias CustomLatLng = LatLng

class MapActivity: BaseMapActivity(), OnMapReadyCallback, GoogleMap.OnMapClickListener {

    private lateinit var mMap: GoogleMap
    private var mLatLng: LatLng? = null
    private var mMarker: Marker? = null
    private var routeSelectionCallback: ((Double, Double) -> Unit)? = null
    private var isInRouteSelectionMode = false
    private val drawMarkers = mutableListOf<Marker>()
    private val searchMarkers = mutableListOf<Marker>()
    private var routeLine: Polyline? = null
    private var navMarkerAnimator: android.animation.ValueAnimator? = null

    // Named awkwardly for history: returns true when NO location is selected (no visible pin).
    // Null-safe: before the map is ready mMarker is null, which also counts as "no pin".
    override fun hasMarker(): Boolean = mMarker?.isVisible != true
    private fun updateMarker(it: LatLng) {
        mMarker?.position = it!!
        mMarker?.isVisible = true
    }
    private fun removeMarker() {
        mMarker?.isVisible = false
        binding.directionButton.visibility = View.GONE
    }
    override fun initializeMap() {
        val mapFragment = SupportMapFragment.newInstance()
        supportFragmentManager.beginTransaction()
            .replace(R.id.map, mapFragment)
            .commit()
        mapFragment?.getMapAsync(this)
    }
    override fun moveMapToNewLocation(moveNewLocation: Boolean) {
        if (moveNewLocation) {
            mLatLng = LatLng(lat, lon)
            mLatLng.let { latLng ->
                mMap.animateCamera(CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                        .target(latLng!!)
                        .zoom(12.0f)
                        .bearing(0f)
                        .tilt(0f)
                        .build()
                ))
                mMarker?.apply {
                    position = latLng
                    isVisible = true
                    showInfoWindow()
                }
            }
        }
    }
    // Feeds Google Maps' blue dot from GPS Setter's OWN real location (platform LocationManager)
    // instead of the gms fused feed, which the stealth hook fakes. We're not a spoof target, so this
    // is the true device position -- red pin = spoof target, blue dot = where you actually are.
    // The blue dot reads our real location (platform GPS), and we stamp the device's compass heading
    // onto each fix so Google Maps redraws the little direction arrow on the dot -- the chevron the
    // SDK only shows when the location carries a bearing (it stopped once we took over the source).
    private val realLocationSource = object : com.google.android.gms.maps.LocationSource,
        android.hardware.SensorEventListener {
        private var listener: com.google.android.gms.maps.LocationSource.OnLocationChangedListener? = null
        private var updates: android.location.LocationListener? = null
        private var lastLocation: android.location.Location? = null
        private var heading = 0f
        private val sensorManager by lazy {
            getSystemService(android.content.Context.SENSOR_SERVICE) as android.hardware.SensorManager
        }
        private val rotationSensor by lazy {
            sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR)
        }

        override fun activate(l: com.google.android.gms.maps.LocationSource.OnLocationChangedListener) {
            listener = l
            updates = requestRealLocationUpdates(2000L) { loc ->
                lastLocation = loc
                push(loc)
            }
            rotationSensor?.let {
                sensorManager.registerListener(this, it, android.hardware.SensorManager.SENSOR_DELAY_UI)
            }
        }

        override fun deactivate() {
            stopRealLocationUpdates(updates)
            runCatching { sensorManager.unregisterListener(this) }
            updates = null; listener = null; lastLocation = null
        }

        private fun push(loc: android.location.Location) {
            loc.bearing = heading
            listener?.onLocationChanged(loc)
        }

        override fun onSensorChanged(event: android.hardware.SensorEvent) {
            if (event.sensor.type != android.hardware.Sensor.TYPE_ROTATION_VECTOR) return
            val rot = FloatArray(9)
            android.hardware.SensorManager.getRotationMatrixFromVector(rot, event.values)
            val orientation = FloatArray(3)
            android.hardware.SensorManager.getOrientation(rot, orientation)
            val newHeading = ((Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0).toFloat()
            // Only re-emit on a meaningful turn, so the arrow rotates without spamming the map.
            if (kotlin.math.abs(newHeading - heading) > 2f) {
                heading = newHeading
                lastLocation?.let { push(android.location.Location(it)) }
            }
        }

        override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        with(mMap){


            // gms custom ui
            setLocationSource(realLocationSource)
            if (ActivityCompat.checkSelfPermission(this@MapActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                setMyLocationEnabled(true);
            } else {
                ActivityCompat.requestPermissions(this@MapActivity, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 99);
            }
            setTrafficEnabled(true)
            uiSettings.isMyLocationButtonEnabled = false
            uiSettings.isZoomControlsEnabled = false
            uiSettings.isCompassEnabled = false
            // The built-in map toolbar (the directions button) sat bottom-right, on top of the
            // getlocation FAB. We disable it and use our own direction button on the opposite side
            // (see binding.directionButton), so it never overlaps the FAB column.
            uiSettings.isMapToolbarEnabled = false
            // Bottom padding lifts the Google logo/attribution above the phone's navigation bar.
            val navClear = (52 * resources.displayMetrics.density).toInt()
            setPadding(0, 80, 0, navClear)
            mapType = viewModel.mapType


            val zoom = 12.0f
            lat = viewModel.getLat
            lon  = viewModel.getLng
            mLatLng = LatLng(lat, lon)
            mLatLng.let {
                mMarker = addMarker(
                    MarkerOptions().position(it!!).draggable(false).icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)).visible(false)
                )
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(it, zoom))
            }

            
            setOnMapClickListener(this@MapActivity)

            // Restore the pin whenever one has been placed -- not only while spoofing is active.
            // The marker is created hidden, so without this the last chosen location was lost on
            // reopen once spoofing had been stopped.
            if (viewModel.hasPin) {
                mMarker?.let { marker ->
                    marker.isVisible = true
                    marker.showInfoWindow()
                }
            }

            // Mirror the old map toolbar: the direction button appears only while the red pin is
            // tapped, and a tap anywhere else on the map dismisses it.
            setOnMarkerClickListener { marker ->
                if (marker.isVisible) binding.directionButton.visibility = View.VISIBLE
                false // keep default behaviour (info window + camera)
            }
        }
    }

    /** Opens turn-by-turn directions to the current pin in Google Maps. */
    private fun openDirections() {
        val marker = mMarker?.takeIf { it.isVisible } ?: return
        val dest = marker.position
        val uri = android.net.Uri.parse(
            "https://www.google.com/maps/dir/?api=1&destination=${dest.latitude},${dest.longitude}"
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
        runCatching {
            startActivity(
                intent.setPackage("com.google.android.apps.maps")
            )
        }.recoverCatching {
            // Google Maps not installed / not resolvable: fall back to any maps handler.
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
        }
    }
    override fun onResume() {
        super.onResume()
        // The map style is chosen in Settings; re-apply it on return so a change takes effect
        // without needing to recreate the map. onMapReady only runs once, when the map is created.
        if (::mMap.isInitialized) {
            mMap.mapType = viewModel.mapType
        }
    }

    override fun onMapClick(latLng: LatLng) {
        // If in route selection mode, handle the callback
        if (isInRouteSelectionMode && routeSelectionCallback != null) {
            routeSelectionCallback?.invoke(latLng.latitude, latLng.longitude)
            return
        }

        // Tapping the map (not the pin) dismisses the direction button, like the old toolbar.
        binding.directionButton.visibility = View.GONE

        // Normal map click behavior
        mLatLng = latLng
        mMarker?.let { marker ->
            mLatLng.let {
                // marker.isVisible = true
                updateMarker(it!!)
                mMap.animateCamera(CameraUpdateFactory.newLatLng(it))
                lat = it.latitude
                lon = it.longitude
            }
        }
        applyLiveIfPlaying()
    }

    override fun getActivityInstance(): BaseMapActivity {
        return this@MapActivity
    }

    @SuppressLint("MissingPermission")
    override fun setupButtons(){
        binding.addfavorite.setOnClickListener {
            addFavoriteDialog()
        }
        binding.getlocation.setOnClickListener {
            getLastLocation()
        }
        binding.autoNavigation?.setOnClickListener {
            openAutoNavigationDialog()
        }
        binding.directionButton.setOnClickListener {
            openDirections()
        }

        if (viewModel.isStarted) {
            binding.startButton.visibility = View.GONE
            binding.stopButton.visibility = View.VISIBLE
        }

        // Keep the Start/Stop buttons in sync with the actual spoof state, so a play/stop done in
        // the floating window -- or a settings reset that stops spoofing -- is reflected here at
        // once, without reopening the screen.
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                io.github.jqssun.gpssetter.utils.PrefManager.spoofingState.collect { started ->
                    binding.startButton.visibility = if (started) View.GONE else View.VISIBLE
                    binding.stopButton.visibility = if (started) View.VISIBLE else View.GONE
                    if (!started) {
                        cancelNotification()
                        binding.autoNavigationProgress.visibility = View.GONE
                    }
                }
            }
        }

        // Keep the pin in sync: a location chosen in the floating window moves the marker here too.
        // The value-diff guard skips changes this screen made itself, avoiding a feedback loop.
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                io.github.jqssun.gpssetter.utils.PrefManager.locationState.collect { (newLat, newLng) ->
                    if (!::mMap.isInitialized) return@collect
                    if (newLat == lat && newLng == lon) return@collect
                    lat = newLat; lon = newLng
                    val p = LatLng(newLat, newLng)
                    mLatLng = p
                    updateMarker(p)
                    mMap.animateCamera(CameraUpdateFactory.newLatLng(p))
                }
            }
        }

        binding.startButton.setOnClickListener {
            viewModel.update(true, lat, lon)
            mLatLng.let {
                updateMarker(it!!)
            }
            binding.startButton.visibility = View.GONE
            binding.stopButton.visibility = View.VISIBLE
            lifecycleScope.launch {
                mLatLng?.getAddress(getActivityInstance())?.let { address ->
                    address.collect{ value ->
                        showStartNotification(value)
                    }
                }
            }
            showToast(getString(R.string.location_set))
        }
        binding.stopButton.setOnClickListener {
            mLatLng.let {
                viewModel.update(false, it!!.latitude, it.longitude)
            }
            // Keep the pin on the map after stopping -- it marks the chosen location whether or not
            // spoofing is playing. The Start/Stop button already signals the active state.
            binding.stopButton.visibility = View.GONE
            binding.startButton.visibility = View.VISIBLE

            // Stop auto navigation if it's running
            stopAutoNavigation()
            binding.autoNavigationProgress.visibility = View.GONE

            cancelNotification()
            showToast(getString(R.string.location_unset))
        }
    }

    override fun updateGPSLocation(latitude: Double, longitude: Double) {
        lat = latitude
        lon = longitude
        val latLng = LatLng(latitude, longitude)
        mLatLng = latLng

        // Smooth marker movement. Cancel any in-flight animation first: navigation delivers a new
        // point every ~200ms, and starting a fresh animator each time without cancelling the last
        // made them stack and fight, which showed up as stutter.
        mMarker?.let { marker ->
            navMarkerAnimator?.cancel()
            val startPosition = marker.position
            navMarkerAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 200 // matches the navigation tick, so movement is continuous
                addUpdateListener { animation ->
                    val fraction = animation.animatedValue as Float
                    val newLat = startPosition.latitude + (latLng.latitude - startPosition.latitude) * fraction
                    val newLng = startPosition.longitude + (latLng.longitude - startPosition.longitude) * fraction
                    marker.position = LatLng(newLat, newLng)
                }
                start()
            }
            marker.isVisible = true
        }

        // Camera follows with the SAME 200ms duration as the marker so they glide together. The
        // old jank was a 300ms camera animation overlapping every 50ms tick; matched to the tick it
        // is smooth.
        mMap.animateCamera(CameraUpdateFactory.newLatLng(latLng), 200, null)

        // Update the GPS mock through the view model
        viewModel.update(true, latitude, longitude)
    }

    override fun setMapClickMode(enabled: Boolean, callback: ((Double, Double) -> Unit)?) {
        isInRouteSelectionMode = enabled
        routeSelectionCallback = callback
    }

    override fun handleNavigationRunning() {
        // Show the existing start/stop button system
        binding.startButton.visibility = View.GONE
        binding.stopButton.visibility = View.VISIBLE

        // Show progress bar for auto navigation
        binding.autoNavigationProgress.visibility = View.VISIBLE

        // Update the view model to show location as started
        viewModel.update(true, lat, lon)
    }

    override fun showRouteOnMap(startLat: Double, startLon: Double, endLat: Double, endLon: Double) {
        // Remove existing route line
        routeLine?.remove()

        // For now, show simple line - will be updated when we get waypoints from NavigationService
        val startPoint = LatLng(startLat, startLon)
        val endPoint = LatLng(endLat, endLon)

        routeLine = mMap.addPolyline(
            PolylineOptions()
                .add(startPoint, endPoint)
                .width(8f)
                .color(android.graphics.Color.BLUE)
                .pattern(listOf(Dash(20f), Gap(10f)))
        )

        // Adjust camera to show entire route
        val bounds = LatLngBounds.Builder()
            .include(startPoint)
            .include(endPoint)
            .build()

        mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100))
    }

    override fun addDrawMarker(lat: Double, lon: Double, index: Int) {
        if (!::mMap.isInitialized) return
        val marker = mMap.addMarker(
            MarkerOptions()
                .position(LatLng(lat, lon))
                .title(index.toString())
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
        )
        marker?.let { drawMarkers.add(it) }
    }

    override fun clearDrawMarkers() {
        drawMarkers.forEach { it.remove() }
        drawMarkers.clear()
    }

    override fun getMapBounds(): DoubleArray? {
        if (!::mMap.isInitialized) return null
        return runCatching {
            val b = mMap.projection.visibleRegion.latLngBounds
            doubleArrayOf(
                b.southwest.latitude, b.southwest.longitude,
                b.northeast.latitude, b.northeast.longitude,
            )
        }.getOrNull()
    }

    override fun showSearchResults(results: List<io.github.jqssun.gpssetter.ui.SearchResult>) {
        if (!::mMap.isInitialized || results.isEmpty()) return
        searchMarkers.forEach { it.remove() }
        searchMarkers.clear()

        val boundsBuilder = LatLngBounds.Builder()
        results.forEach { r ->
            val pos = LatLng(r.lat, r.lon)
            boundsBuilder.include(pos)
            mMap.addMarker(
                MarkerOptions().position(pos).title(r.label)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
            )?.let { searchMarkers.add(it) }
        }
        // Tapping a result pin sets the location there and clears the rest.
        mMap.setOnMarkerClickListener { marker ->
            if (marker in searchMarkers) {
                lat = marker.position.latitude
                lon = marker.position.longitude
                moveMapToNewLocation(true)
                applyLiveIfPlaying()
                searchMarkers.forEach { it.remove() }
                searchMarkers.clear()
                mMap.setOnMarkerClickListener(null)
                true
            } else false
        }
        // One pin: a bounds box would be zero-size and fail, so zoom straight to it. Many pins:
        // frame them all.
        runCatching {
            if (results.size == 1) {
                mMap.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(results[0].lat, results[0].lon), 16f)
                )
            } else {
                mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 120))
            }
        }
        showToast(getString(R.string.search_results_count, results.size))
    }

    override fun showRouteWithWaypoints(waypoints: List<CustomLatLng>) {
        // Remove existing route line
        routeLine?.remove()

        if (waypoints.size < 2) return

        // Create route line with all waypoints
        val polylineOptions = PolylineOptions()
            .width(8f)
            .color(android.graphics.Color.BLUE)
            .pattern(listOf(Dash(20f), Gap(10f)))

        waypoints.forEach { polylineOptions.add(it) }
        routeLine = mMap.addPolyline(polylineOptions)

        // Adjust camera to show entire route
        val boundsBuilder = LatLngBounds.Builder()
        waypoints.forEach { boundsBuilder.include(it) }
        val bounds = boundsBuilder.build()

        mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100))
    }

    override fun handleNavigationStopped() {
        // Reset to start button
        binding.stopButton.visibility = View.GONE
        binding.startButton.visibility = View.VISIBLE

        // Hide progress bar
        binding.autoNavigationProgress.visibility = View.GONE

        // Remove route line
        routeLine?.remove()
        routeLine = null

        // Update the view model to show location as stopped
        viewModel.update(false, lat, lon)
    }

    override fun updateNavigationProgress(progress: Int) {
        binding.autoNavigationProgress.progress = progress
    }

}

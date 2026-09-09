package io.github.jqssun.gpssetter.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.Color
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.view.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.ElevationOverlayProvider
import com.google.android.material.progressindicator.LinearProgressIndicator
import dagger.hilt.android.AndroidEntryPoint
import io.github.jqssun.gpssetter.BuildConfig
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.adapter.FavListAdapter
import io.github.jqssun.gpssetter.databinding.ActivityMapBinding
import io.github.jqssun.gpssetter.ui.viewmodel.MainViewModel
import io.github.jqssun.gpssetter.utils.JoystickService
import io.github.jqssun.gpssetter.utils.FloatingMapService
import io.github.jqssun.gpssetter.utils.NotificationsChannel
import io.github.jqssun.gpssetter.utils.PrefManager
import io.github.jqssun.gpssetter.utils.ext.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import java.io.IOException
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.properties.Delegates

@AndroidEntryPoint
abstract class BaseMapActivity: AppCompatActivity() {

    protected var lat by Delegates.notNull<Double>()
    protected var lon by Delegates.notNull<Double>()
    protected val viewModel by viewModels<MainViewModel>()
    protected val binding by lazy { ActivityMapBinding.inflate(layoutInflater) }
    protected lateinit var alertDialog: MaterialAlertDialogBuilder
    protected lateinit var dialog: AlertDialog

    private val notificationsChannel by lazy { NotificationsChannel() }
    private var favListAdapter: FavListAdapter = FavListAdapter()
    private var xposedDialog: AlertDialog? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val PERMISSION_ID = 42
    private var autoNavigationDialog: AutoNavigationDialog? = null

    private val elevationOverlayProvider by lazy {
        ElevationOverlayProvider(this)
    }

    private val headerBackground by lazy {
        elevationOverlayProvider.compositeOverlayWithThemeSurfaceColorIfNeeded(
            resources.getDimension(R.dimen.bottom_sheet_elevation)
        )
    }

    protected abstract fun getActivityInstance(): BaseMapActivity
    protected abstract fun hasMarker(): Boolean
    protected abstract fun initializeMap()
    protected abstract fun setupButtons()
    protected abstract fun moveMapToNewLocation(moveNewLocation: Boolean)
    abstract fun updateGPSLocation(latitude: Double, longitude: Double)
    abstract fun setMapClickMode(enabled: Boolean, callback: ((Double, Double) -> Unit)?)
    abstract fun handleNavigationRunning()
    abstract fun handleNavigationStopped()
    abstract fun updateNavigationProgress(progress: Int)
    abstract fun showRouteOnMap(startLat: Double, startLon: Double, endLat: Double, endLon: Double)
    abstract fun showRouteWithWaypoints(waypoints: List<CustomLatLng>)

    /** Drops a numbered pin while drawing a route (Z-mode); [index] is 1-based. */
    abstract fun addDrawMarker(lat: Double, lon: Double, index: Int)

    /** Removes all Z-mode drawing pins. */
    abstract fun clearDrawMarkers()

    /** Current visible map bounds as [swLat, swLon, neLat, neLon], or null if the map is not ready. */
    abstract fun getMapBounds(): DoubleArray?

    /** Drops a pin for each search result and frames them; taps set the location. */
    abstract fun showSearchResults(results: List<SearchResult>)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        initializeMap()
        checkModuleEnabled()
        checkUpdates()
        setupNavView()
        setupButtons()
        setupDrawer()
        // Single observer for favourite saves -- see addFavoriteDialog for why it is not per-dialog.
        viewModel.response.observe(this) {
            showToast(getString(if (it == (-1).toLong()) R.string.cant_save else R.string.save))
        }
        if (PrefManager.isJoystickEnabled){
            startService(Intent(this, JoystickService::class.java))
        }
    }

    private fun setupDrawer() {
        supportActionBar?.setDisplayShowTitleEnabled(false)
        val mDrawerToggle = object : ActionBarDrawerToggle(
            this,
            binding.container,
            binding.toolbar,
            R.string.drawer_open,
            R.string.drawer_close
        ) {
            override fun onDrawerClosed(view: View) {
                super.onDrawerClosed(view)
                invalidateOptionsMenu()
            }

            override fun onDrawerOpened(drawerView: View) {
                super.onDrawerOpened(drawerView)
                invalidateOptionsMenu()
            }
        }
        binding.container.addDrawerListener(mDrawerToggle)
    }

    private fun setupNavView() {

        ViewCompat.setOnApplyWindowInsetsListener(binding.mapContainer.map) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.navView.setPadding(0, bars.top, 0, 0)
            WindowInsetsCompat.CONSUMED
        }

        val progress = binding.search.searchProgress
        binding.search.searchBox.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                if (isNetworkConnected()) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        val getInput = v.text.toString()
                        if (getInput.isNotEmpty()){
                            getSearchAddress(getInput).let {
                                it.collect { result ->
                                    when(result) {
                                        is SearchProgress.Progress -> {
                                            progress.visibility = View.VISIBLE
                                        }
                                        is SearchProgress.Complete -> {
                                            progress.visibility = View.GONE
                                            lat = result.lat
                                            lon = result.lon
                                            moveMapToNewLocation(true)
                                        }
                                        is SearchProgress.Results -> {
                                            progress.visibility = View.GONE
                                            showSearchResults(result.places)
                                        }
                                        is SearchProgress.Fail -> {
                                            progress.visibility = View.GONE
                                            showToast(result.error!!)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    showToast(getString(R.string.no_internet))
                }
                return@setOnEditorActionListener true
            }
            return@setOnEditorActionListener false
        }

        binding.navView.setNavigationItemSelectedListener {
            when(it.itemId){
                R.id.get_favorite -> {
                    openFavoriteListDialog()
                }
                R.id.floating_map -> {
                    toggleFloatingMap()
                }
                R.id.settings -> {
                    startActivity(Intent(this,ActivitySettings::class.java))
                }
                R.id.about -> {
                    aboutDialog()
                }
            }
            binding.container.closeDrawer(GravityCompat.START)
            true
        }
    }

    /** Opens the floating map overlay, or closes it if it is already showing. Needs overlay access. */
    private fun toggleFloatingMap() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        val intent = Intent(this, FloatingMapService::class.java)
        if (FloatingMapService.isRunning) {
            stopService(intent)
            showToast(getString(R.string.floating_map_closed))
        } else {
            startService(intent)
            showToast(getString(R.string.floating_map_opened))
        }
    }

    private fun checkModuleEnabled(){
        viewModel.isXposed.observe(this) { isXposed ->
            xposedDialog?.dismiss()
            xposedDialog = null
            if (!isXposed) {
                xposedDialog = MaterialAlertDialogBuilder(this).run {
                    setTitle(R.string.error_xposed_module_missing)
                    setMessage(R.string.error_xposed_module_missing_desc)
                    // setCancelable(BuildConfig.DEBUG)
                    setCancelable(true)
                    show()
                }
            }
        }
    }

    protected fun aboutDialog(){
        alertDialog = MaterialAlertDialogBuilder(this)
        layoutInflater.inflate(R.layout.about,null).apply {
            val  titlele = findViewById<TextView>(R.id.design_about_title)
            val  version = findViewById<TextView>(R.id.design_about_version)
            val  info = findViewById<TextView>(R.id.design_about_info)
            titlele.text = getString(R.string.app_name)
            version.text = BuildConfig.VERSION_NAME
            info.text = androidx.core.text.HtmlCompat.fromHtml(
                getString(R.string.about_info),
                androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY
            )
            info.movementMethod = android.text.method.LinkMovementMethod.getInstance()
        }.run {
            alertDialog.setView(this)
            alertDialog.show()
        }
    }

    protected fun addFavoriteDialog() {
        alertDialog =  MaterialAlertDialogBuilder(this).apply {
            val view = layoutInflater.inflate(R.layout.dialog,null)
            val editText = view.findViewById<EditText>(R.id.search_edittxt)
            val descriptionText = view.findViewById<EditText>(R.id.description_edittxt)
            setTitle(getString(R.string.add_fav_dialog_title))
            setPositiveButton(getString(R.string.dialog_button_add)) { _, _ ->
                val s = editText.text.toString()
                if (hasMarker()){
                  showToast(getString(R.string.location_not_select))
                }else{
                    // The save result is observed once in onCreate -- observing here would add a
                    // fresh observer every time the dialog opens, so a later save fired many toasts.
                    viewModel.storeFavorite(s, lat, lon, descriptionText.text.toString())
                }
            }
            setView(view)
            show()
        }
    }

    /**
     * Applies a newly chosen point to the LIVE spoof if playing, so picking a location by tapping
     * the map, tapping a search pin, or choosing a favourite moves the fake position immediately --
     * no stop/start needed. When not playing, it just stages the point for the Start button.
     */
    protected fun applyLiveIfPlaying() {
        // Persist the chosen point so the red pin returns on reopen even when stopped; keep the
        // spoof live only if it is already playing. update() also records that a pin now exists.
        viewModel.update(viewModel.isStarted, lat, lon)
    }

    private fun openFavoriteListDialog() {
        getAllUpdatedFavList()
        alertDialog = MaterialAlertDialogBuilder(this)
        alertDialog.setTitle(getString(R.string.favorites))
        val view = layoutInflater.inflate(R.layout.fav,null)
        val rcv = view.findViewById<RecyclerView>(R.id.favorites_list)
        rcv.layoutManager = LinearLayoutManager(this)
        rcv.adapter = favListAdapter
        favListAdapter.onItemClick = {
            it.let {
                lat = it.lat!!
                lon = it.lng!!
            }
            moveMapToNewLocation(true)
            applyLiveIfPlaying()
            if (dialog.isShowing) dialog.dismiss()

        }
        favListAdapter.onItemDelete = {
            viewModel.deleteFavorite(it)
        }
        alertDialog.setView(view)
        dialog = alertDialog.create()
        dialog.show()

    }

    private fun getAllUpdatedFavList(){
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED){
                viewModel.doGetUserDetails()
                viewModel.allFavList.collect {
                    favListAdapter.submitList(it)
                }
            }
        }

    }

    private fun checkUpdates(){
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.update.collect { upd ->
                    if (upd != null) showUpdatePrompt(viewModel, upd)
                }
            }
        }
    }

    /**
     * A national-address short code (Saudi format: four letters then four digits, e.g. JHUA8131).
     * These are searched worldwide, like plus codes; everything else is treated as a place name and
     * bounded to the visible map area.
     */
    private fun looksLikeNationalAddress(query: String): Boolean =
        Regex("^[A-Za-z]{4}\\d{4}$").matches(query.trim())

    /** Removes search hits within ~40 m of an earlier one, across the combined sources. */
    private fun dedupResults(all: List<SearchResult>): List<SearchResult> {
        val kept = mutableListOf<SearchResult>()
        for (r in all) {
            val dup = kept.any {
                val dLat = it.lat - r.lat; val dLon = it.lon - r.lon
                dLat * dLat + dLon * dLon < 0.0000001
            }
            if (!dup) kept.add(r)
        }
        return kept
    }

    private suspend fun getSearchAddress(address: String) = callbackFlow {
        withContext(Dispatchers.IO){
            trySend(SearchProgress.Progress)
            val matcher: Matcher =
                Pattern.compile("[-+]?\\d{1,3}([.]\\d+)?, *[-+]?\\d{1,3}([.]\\d+)?").matcher(address)

            if (matcher.matches()){
                val la = matcher.group().split(",")[0].trim().toDouble()
                val lo = matcher.group().split(",")[1].trim().toDouble()
                trySend(SearchProgress.Results(listOf(SearchResult(la, lo, "$la, $lo"))))
            }else {
                // Try a location code first (Plus Code). Read the map bounds on the MAIN thread --
                // the map projection can only be queried there; off-thread it returns null, which
                // previously disabled the "current view only" filter and let far-away hits through.
                val bounds0 = withContext(Dispatchers.Main) { getMapBounds() }
                val refLat = bounds0?.let { (it[0] + it[2]) / 2 }
                val refLon = bounds0?.let { (it[1] + it[3]) / 2 }
                val coord = io.github.jqssun.gpssetter.utils.CodeResolver.resolve(address, refLat, refLon)
                if (coord != null) {
                    trySend(SearchProgress.Results(listOf(SearchResult(coord.lat, coord.lon, address))))
                    return@withContext
                }

                // Geocoder is backed by Google Play Services on this device, so it handles place
                // names and Arabic queries, not just street addresses. Bias results to the area the
                // map is currently showing so a search like a restaurant name finds nearby matches.
                val geocoder = Geocoder(getActivityInstance())
                try {
                    @Suppress("DEPRECATION")
                    val addressList: List<Address> =
                        geocoder.getFromLocationName(address, 10) ?: emptyList()

                    val deviceAll = addressList.map { a ->
                        val label = a.getAddressLine(0)
                            ?: a.featureName ?: "${a.latitude}, ${a.longitude}"
                        SearchResult(a.latitude, a.longitude, label)
                    }
                    // Only a national-address short code (e.g. JHUA8131) is searched WORLDWIDE, like
                    // a plus code. Anything else typed into the geocoder is a place name -- bound it
                    // to the visible area, so a query like "واقف" no longer returns hits from across
                    // the country.
                    val deviceResults =
                        if (looksLikeNationalAddress(address) || bounds0 == null) deviceAll
                        else deviceAll.filter { r ->
                            r.lat in bounds0[0]..bounds0[2] && r.lon in bounds0[1]..bounds0[3]
                        }
                    // Place/POI search (Google Places + OpenStreetMap) IS bounded: keep only hits
                    // inside the current view. Google leads, so it is listed first.
                    val places = io.github.jqssun.gpssetter.utils.PlacesService
                        .searchAll(address, bounds0)
                    val placesInView = if (bounds0 == null) places else places.filter { r ->
                        r.lat in bounds0[0]..bounds0[2] && r.lon in bounds0[1]..bounds0[3]
                    }
                    val combined = dedupResults(placesInView + deviceResults)

                    if (combined.isEmpty()) {
                        trySend(SearchProgress.Fail(getString(R.string.address_not_found)))
                    } else {
                        // Always show results as orange pins -- the red (spoof) pin only moves when
                        // the user taps one, so searching never changes the set location by itself.
                        trySend(SearchProgress.Results(combined))
                    }
                } catch (io : IOException){
                    trySend(SearchProgress.Fail(getString(R.string.no_internet)))
                }
            }
        }
        awaitClose { this.cancel() }
    }

    protected fun showStartNotification(address: String){
        notificationsChannel.showNotification(this){
            it.setSmallIcon(R.drawable.ic_stop)
            it.setContentTitle(getString(R.string.location_set))
            it.setContentText(address)
            it.setAutoCancel(true)
            it.setCategory(Notification.CATEGORY_EVENT)
            it.priority = NotificationCompat.PRIORITY_HIGH
        }
    }

    protected fun cancelNotification(){
        notificationsChannel.cancelAllNotifications(this)
    }

    /**
     * Fetches the device's REAL location (not the spoofed one) and hands it back. Our own app is
     * excluded from the module's hooks, so its location APIs return the genuine device position --
     * which is what the navigation dialog's "Use current location" needs.
     */
    @SuppressLint("MissingPermission")
    fun fetchRealLocation(onResult: (Double, Double) -> Unit) {
        if (!checkPermissions()) { requestPermissions(); return }
        if (!isLocationEnabled()) {
            showToast(getString(R.string.no_real_location))
            return
        }
        LocationServices.getFusedLocationProviderClient(this).lastLocation
            .addOnSuccessListener { loc ->
                if (loc != null) onResult(loc.latitude, loc.longitude)
                else showToast(getString(R.string.no_real_location))
            }
            .addOnFailureListener { showToast(getString(R.string.no_real_location)) }
    }

    // Get current location
    @SuppressLint("MissingPermission")
    protected fun getLastLocation() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        if (checkPermissions()) {
            if (isLocationEnabled()) {
                fusedLocationClient.lastLocation.addOnCompleteListener(this) { task ->
                    val location: Location? = task.result
                    if (location == null) {
                        requestNewLocationData()
                    } else {
                        lat = location.latitude
                        lon = location.longitude
                        moveMapToNewLocation(true)
                    }
                }
            } else {
                showToast("Turn on location")
                val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                startActivity(intent)
            }
        } else {
            requestPermissions()
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestNewLocationData() {
        val mLocationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 0)
            .setMaxUpdates(1)
            .build()

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        fusedLocationClient.requestLocationUpdates(
            mLocationRequest, mLocationCallback,
            Looper.myLooper()
        )
    }

    private val mLocationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val mLastLocation: Location = locationResult.lastLocation!!
            lat = mLastLocation.latitude
            lon = mLastLocation.longitude
        }
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager: LocationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(
            LocationManager.NETWORK_PROVIDER
        )
    }

    private fun checkPermissions(): Boolean {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }
        return false
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
            PERMISSION_ID
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
            getLastLocation()
        }
    }

    private val drawnPoints = mutableListOf<Pair<Double, Double>>()
    private var onRouteDrawn: ((List<Pair<Double, Double>>) -> Unit)? = null

    /**
     * Red Alert 2 style route drawing: the user taps the map to lay down points in order, then
     * Done. The points are handed back so the caller can turn them into a road route.
     */
    fun beginRouteDrawing(onDone: (List<Pair<Double, Double>>) -> Unit) {
        drawnPoints.clear()
        clearDrawMarkers()
        onRouteDrawn = onDone
        binding.drawRouteBar.visibility = View.VISIBLE
        updateDrawHint()

        setMapClickMode(true) { lat, lon ->
            drawnPoints.add(lat to lon)
            addDrawMarker(lat, lon, drawnPoints.size)
            updateDrawHint()
        }

        binding.drawUndoBtn.setOnClickListener {
            if (drawnPoints.isNotEmpty()) {
                drawnPoints.removeAt(drawnPoints.lastIndex)
                clearDrawMarkers()
                drawnPoints.forEachIndexed { i, p -> addDrawMarker(p.first, p.second, i + 1) }
                updateDrawHint()
            }
        }
        binding.drawCancelBtn.setOnClickListener { endRouteDrawing(commit = false) }
        binding.drawDoneBtn.setOnClickListener { endRouteDrawing(commit = true) }
    }

    private fun updateDrawHint() {
        binding.drawRouteHint.text = getString(R.string.draw_route_hint) + "  (${drawnPoints.size})"
        binding.drawDoneBtn.isEnabled = drawnPoints.size >= 2
    }

    private fun endRouteDrawing(commit: Boolean) {
        setMapClickMode(false, null)
        binding.drawRouteBar.visibility = View.GONE
        clearDrawMarkers()
        val points = drawnPoints.toList()
        drawnPoints.clear()
        val cb = onRouteDrawn
        onRouteDrawn = null
        if (commit && points.size >= 2) cb?.invoke(points)
    }

    protected fun openAutoNavigationDialog() {
        autoNavigationDialog = AutoNavigationDialog(this, lat, lon)
        autoNavigationDialog?.show()
    }

    protected fun stopAutoNavigation() {
        autoNavigationDialog?.stopNavigation()
    }

    override fun onDestroy() {
        autoNavigationDialog?.cleanup()
        autoNavigationDialog = null
        super.onDestroy()
    }
}

data class SearchResult(val lat: Double, val lon: Double, val label: String)

sealed class SearchProgress {
    object Progress : SearchProgress()
    data class Complete(val lat: Double , val lon : Double) : SearchProgress()
    data class Results(val places: List<SearchResult>) : SearchProgress()
    data class Fail(val error: String?) : SearchProgress()
}

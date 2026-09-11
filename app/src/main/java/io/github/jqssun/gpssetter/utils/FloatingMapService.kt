package io.github.jqssun.gpssetter.utils

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import io.github.jqssun.gpssetter.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.max

/**
 * A draggable, resizable floating window that hovers over other apps and shows the Google map, so
 * the spoof point can be moved without leaving the current app. Tapping the map sets the location
 * live when spoofing is active.
 *
 * A Google MapView normally lives inside an Activity; here it runs inside a Service, so its
 * lifecycle callbacks (onCreate/onResume/onPause/onDestroy/onLowMemory) are forwarded by hand.
 * This is the fragile part -- if Google's map refuses to render in an overlay on a given device,
 * this is where it shows.
 */
class FloatingMapService : Service() {

    private var wm: WindowManager? = null
    private var root: View? = null
    private var mapView: MapView? = null
    private var map: GoogleMap? = null
    private var marker: Marker? = null
    private lateinit var params: WindowManager.LayoutParams

    private val minSize by lazy { (220 * resources.displayMetrics.density).toInt() }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    override fun onCreate() {
        super.onCreate()
        isRunning = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        root = inflater.inflate(R.layout.floating_map, null)

        val density = resources.displayMetrics.density
        params = WindowManager.LayoutParams(
            (300 * density).toInt(),
            (360 * density).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NO_LIMITS lets the window move freely instead of being clamped to the screen edges.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (24 * density).toInt()
            y = (120 * density).toInt()
        }

        setupMap()
        setupDrag()
        setupResize()
        setupHide()
        setupPlayStop()

        runCatching { wm?.addView(root, params) }
            .onFailure { Timber.e(it, "could not add floating map window") }
    }

    private var minimized = false
    private var savedWidth = 0
    private var savedHeight = 0

    /**
     * Top-left button. A tap collapses the window down to just this button as a small floating
     * bubble (and a tap on the bubble expands it back); dragging it moves the window, and dropping
     * it on the red target closes it -- so the bubble behaves like the full window, minimized or not.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupHide() {
        val btn = root?.findViewById<ImageView>(R.id.floating_hide) ?: return
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f; var dragging = false
        btn.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = event.rawX; touchY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX; val dy = event.rawY - touchY
                    if (!dragging && kotlin.math.hypot(dx.toDouble(), dy.toDouble()) > slop) {
                        dragging = true
                        showDismissZone()
                    }
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        runCatching { wm?.updateViewLayout(root, params) }
                        highlightDismissIfOver(event.rawX, event.rawY)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        val overDismiss = isOverDismiss(event.rawX, event.rawY)
                        hideDismissZone()
                        if (overDismiss) stopSelf()
                    } else {
                        toggleMinimized()
                    }
                    true
                }
                else -> false
            }
        }
    }

    /** Shrinks to just the bubble button, or restores the full map window. */
    private fun toggleMinimized() {
        val btn = root?.findViewById<ImageView>(R.id.floating_hide) ?: return
        val bubble = (56 * resources.displayMetrics.density).toInt()
        minimized = !minimized
        if (minimized) {
            savedWidth = params.width; savedHeight = params.height
            mapView?.visibility = View.GONE
            root?.findViewById<View>(R.id.floating_resize)?.visibility = View.GONE
            root?.findViewById<View>(R.id.floating_play_stop)?.visibility = View.GONE
            root?.findViewById<View>(R.id.floating_drag_bar)?.visibility = View.GONE
            // Drop the dark window background so only the round bubble shows.
            root?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            params.width = bubble; params.height = bubble
            btn.setImageResource(android.R.drawable.ic_menu_compass)
        } else {
            mapView?.visibility = View.VISIBLE
            root?.findViewById<View>(R.id.floating_resize)?.visibility = View.VISIBLE
            root?.findViewById<View>(R.id.floating_play_stop)?.visibility = View.VISIBLE
            root?.findViewById<View>(R.id.floating_drag_bar)?.visibility = View.VISIBLE
            root?.setBackgroundColor(0xDD202124.toInt())
            if (savedWidth > 0) params.width = savedWidth
            if (savedHeight > 0) params.height = savedHeight
            btn.setImageResource(android.R.drawable.arrow_down_float)
        }
        runCatching { wm?.updateViewLayout(root, params) }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Top-right button: start/stop spoofing at the current pin, mirroring the main screen. */
    private fun setupPlayStop() {
        val btn = root?.findViewById<ImageView>(R.id.floating_play_stop) ?: return
        btn.setOnClickListener {
            // Toggle from the flow's value (updated synchronously) rather than re-reading prefs,
            // whose write is asynchronous -- reading it back here would see the stale state.
            val (lat, lng) = PrefManager.locationState.value
            PrefManager.update(!PrefManager.spoofingState.value, lat, lng)
        }
        // Follow the shared state, so the icon reflects a start/stop done on the main screen too.
        serviceScope.launch {
            PrefManager.spoofingState.collect { started ->
                btn.setImageResource(if (started) R.drawable.ic_stop else R.drawable.ic_play)
            }
        }
    }

    private fun setupMap() {
        mapView = root?.findViewById(R.id.floating_mapview)
        runCatching {
            MapsInitializer.initialize(applicationContext)
            mapView?.onCreate(Bundle())
            mapView?.onResume()
            mapView?.getMapAsync { googleMap ->
                map = googleMap
                googleMap.mapType = PrefManager.mapType
                val start = LatLng(PrefManager.getLat, PrefManager.getLng)
                marker = googleMap.addMarker(MarkerOptions().position(start))
                googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(start, 14f))

                // Tapping the floating map sets the spoof point; live when playing.
                googleMap.setOnMapClickListener { latLng ->
                    marker?.position = latLng
                    PrefManager.update(PrefManager.spoofingState.value, latLng.latitude, latLng.longitude)
                }

                // Follow the shared pin, so a location chosen on the main screen moves this marker.
                serviceScope.launch {
                    PrefManager.locationState.collect { (lat, lng) ->
                        val pos = LatLng(lat, lng)
                        marker?.position = pos
                        googleMap.moveCamera(CameraUpdateFactory.newLatLng(pos))
                    }
                }
            }
        }.onFailure { Timber.e(it, "floating map init failed") }
    }

    private var dismissView: View? = null

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag() {
        val grip = root?.findViewById<View>(R.id.floating_drag_bar) ?: return
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
        grip.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = event.rawX; touchY = event.rawY
                    showDismissZone()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - touchX).toInt()
                    params.y = startY + (event.rawY - touchY).toInt()
                    runCatching { wm?.updateViewLayout(root, params) }
                    highlightDismissIfOver(event.rawX, event.rawY)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val overDismiss = isOverDismiss(event.rawX, event.rawY)
                    hideDismissZone()
                    if (overDismiss) stopSelf()
                    true
                }
                else -> false
            }
        }
    }

    /** A red target at the bottom-centre; dropping the window on it closes the window. */
    @SuppressLint("InflateParams")
    private fun showDismissZone() {
        if (dismissView != null) return
        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val view = inflater.inflate(R.layout.floating_dismiss, null)
        val size = (72 * resources.displayMetrics.density).toInt()
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (48 * resources.displayMetrics.density).toInt()
        }
        dismissView = view
        runCatching { wm?.addView(view, lp) }
    }

    private fun hideDismissZone() {
        dismissView?.let { runCatching { wm?.removeView(it) } }
        dismissView = null
    }

    /** Screen rectangle of the dismiss target, for hit-testing the drag release. */
    private fun dismissBounds(): IntArray? {
        val v = dismissView?.findViewById<View>(R.id.dismiss_icon) ?: return null
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return intArrayOf(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
    }

    private fun isOverDismiss(rawX: Float, rawY: Float): Boolean {
        val b = dismissBounds() ?: return false
        // Generous margin so it triggers as the window nears the target.
        val m = 40 * resources.displayMetrics.density
        return rawX in (b[0] - m)..(b[2] + m) && rawY in (b[1] - m)..(b[3] + m)
    }

    private fun highlightDismissIfOver(rawX: Float, rawY: Float) {
        dismissView?.findViewById<View>(R.id.dismiss_icon)?.alpha =
            if (isOverDismiss(rawX, rawY)) 1f else 0.6f
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupResize() {
        val handle = root?.findViewById<View>(R.id.floating_resize) ?: return
        var startW = 0; var startH = 0; var touchX = 0f; var touchY = 0f
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startW = params.width; startH = params.height
                    touchX = event.rawX; touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.width = max(minSize, startW + (event.rawX - touchX).toInt())
                    params.height = max(minSize, startH + (event.rawY - touchY).toInt())
                    runCatching { wm?.updateViewLayout(root, params) }
                    true
                }
                else -> false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        serviceScope.cancel()
        hideDismissZone()
        runCatching { mapView?.onPause(); mapView?.onDestroy() }
        root?.let { runCatching { wm?.removeView(it) } }
        root = null
    }

    companion object {
        /** Whether the overlay is currently up. Lets callers avoid the deprecated getRunningServices. */
        @Volatile
        var isRunning = false
            private set
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

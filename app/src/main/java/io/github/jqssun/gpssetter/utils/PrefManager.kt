package io.github.jqssun.gpssetter.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import io.github.jqssun.gpssetter.BuildConfig
import io.github.jqssun.gpssetter.gsApp
import io.github.jqssun.gpssetter.xposed.XposedPrefs
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Settings store for the app process.
 *
 * The legacy module exposed a `MODE_WORLD_READABLE` SharedPreferences file that the hook read back
 * with `XSharedPreferences`. The modern API has neither: the framework owns the shared state and
 * hands it to the module through remote preferences. So writes are mirrored -- the local private
 * file stays the source of truth for the UI (it is synchronous and always available, even before
 * the Xposed service binds or when the module is disabled), and every write is also pushed to the
 * framework's remote preferences, which is what the hook reads.
 */
object PrefManager {

    private val local: SharedPreferences by lazy {
        gsApp.getSharedPreferences("${BuildConfig.APPLICATION_ID}_prefs", Context.MODE_PRIVATE)
    }

    @Volatile
    private var remote: SharedPreferences? = null

    /**
     * Called by [io.github.jqssun.gpssetter.App] when the Xposed service binds or dies. On bind the
     * current local state is pushed once so the module sees settings made while it was unbound.
     */
    fun attachService(service: XposedService?) {
        val prefs = service?.let {
            runCatching { it.getRemotePreferences(XposedPrefs.GROUP) }.getOrNull()
        }
        remote = prefs
        if (prefs != null) {
            runInBackground { pushAll(prefs) }
        }
    }

    private fun pushAll(prefs: SharedPreferences) {
        val editor = prefs.edit()
        for ((key, value) in local.all) {
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is String -> editor.putString(key, value)
            }
        }
        editor.apply()
    }

    /** Applies [block] to the local file and, when available, to the remote preferences. */
    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        local.edit().apply(block).apply()
        remote?.edit()?.apply(block)?.apply()
    }

    val isStarted: Boolean
        get() = local.getBoolean(XposedPrefs.START, false)

    val getLat: Double
        get() = local.getFloat(XposedPrefs.LATITUDE, 40.7128F).toDouble()

    val getLng: Double
        get() = local.getFloat(XposedPrefs.LONGITUDE, -74.0060F).toDouble()

    var isSystemHooked: Boolean
        get() = local.getBoolean(XposedPrefs.HOOKED_SYSTEM, XposedPrefs.Defaults.HOOKED_SYSTEM)
        set(value) = edit { putBoolean(XposedPrefs.HOOKED_SYSTEM, value) }

    var isRandomPosition: Boolean
        get() = local.getBoolean(XposedPrefs.RANDOM_POSITION, false)
        set(value) = edit { putBoolean(XposedPrefs.RANDOM_POSITION, value) }

    var accuracy: String?
        get() = local.getString(XposedPrefs.ACCURACY_SETTING, "10")
        set(value) = edit { putString(XposedPrefs.ACCURACY_SETTING, value) }

    var mapType: Int
        get() = local.getInt(XposedPrefs.MAP_TYPE, 1)
        set(value) = edit { putInt(XposedPrefs.MAP_TYPE, value) }

    var darkTheme: Int
        get() = local.getInt(XposedPrefs.DARK_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        set(value) = edit { putInt(XposedPrefs.DARK_THEME, value) }

    var isUpdateDisabled: Boolean
        get() = local.getBoolean(XposedPrefs.DISABLE_UPDATE, false)
        set(value) = edit { putBoolean(XposedPrefs.DISABLE_UPDATE, value) }

    /** Release tag the user ignored (e.g. "v2.0.1"); a newer release supersedes it. */
    var ignoredUpdateVersion: String
        get() = local.getString(XposedPrefs.IGNORED_UPDATE_VERSION, "")!!
        set(value) = edit { putString(XposedPrefs.IGNORED_UPDATE_VERSION, value) }

    var isJoystickEnabled: Boolean
        get() = local.getBoolean(XposedPrefs.ENABLE_JOYSTICK, false)
        set(value) = edit { putBoolean(XposedPrefs.ENABLE_JOYSTICK, value) }

    // --- spoof value settings --------------------------------------------

    var randomizeRadius: Float
        get() = local.getFloat(XposedPrefs.RANDOMIZE_RADIUS, XposedPrefs.Defaults.RANDOMIZE_RADIUS)
        set(value) = edit { putFloat(XposedPrefs.RANDOMIZE_RADIUS, value) }

    var useAltitude: Boolean
        get() = local.getBoolean(XposedPrefs.USE_ALTITUDE, false)
        set(value) = edit { putBoolean(XposedPrefs.USE_ALTITUDE, value) }
    var altitude: Float
        get() = local.getFloat(XposedPrefs.ALTITUDE, XposedPrefs.Defaults.ALTITUDE)
        set(value) = edit { putFloat(XposedPrefs.ALTITUDE, value) }

    var useMsl: Boolean
        get() = local.getBoolean(XposedPrefs.USE_MSL, false)
        set(value) = edit { putBoolean(XposedPrefs.USE_MSL, value) }
    var msl: Float
        get() = local.getFloat(XposedPrefs.MSL, XposedPrefs.Defaults.MSL)
        set(value) = edit { putFloat(XposedPrefs.MSL, value) }

    var useMslAccuracy: Boolean
        get() = local.getBoolean(XposedPrefs.USE_MSL_ACCURACY, false)
        set(value) = edit { putBoolean(XposedPrefs.USE_MSL_ACCURACY, value) }
    var mslAccuracy: Float
        get() = local.getFloat(XposedPrefs.MSL_ACCURACY, XposedPrefs.Defaults.MSL_ACCURACY)
        set(value) = edit { putFloat(XposedPrefs.MSL_ACCURACY, value) }

    var useVerticalAccuracy: Boolean
        get() = local.getBoolean(XposedPrefs.USE_VERTICAL_ACCURACY, false)
        set(value) = edit { putBoolean(XposedPrefs.USE_VERTICAL_ACCURACY, value) }
    var verticalAccuracy: Float
        get() = local.getFloat(XposedPrefs.VERTICAL_ACCURACY, XposedPrefs.Defaults.VERTICAL_ACCURACY)
        set(value) = edit { putFloat(XposedPrefs.VERTICAL_ACCURACY, value) }

    var useSpeed: Boolean
        get() = local.getBoolean(XposedPrefs.USE_SPEED, false)
        set(value) = edit { putBoolean(XposedPrefs.USE_SPEED, value) }
    var speed: Float
        get() = local.getFloat(XposedPrefs.SPEED, XposedPrefs.Defaults.SPEED)
        set(value) = edit { putFloat(XposedPrefs.SPEED, value) }

    var useSpeedAccuracy: Boolean
        get() = local.getBoolean(XposedPrefs.USE_SPEED_ACCURACY, false)
        set(value) = edit { putBoolean(XposedPrefs.USE_SPEED_ACCURACY, value) }
    var speedAccuracy: Float
        get() = local.getFloat(XposedPrefs.SPEED_ACCURACY, XposedPrefs.Defaults.SPEED_ACCURACY)
        set(value) = edit { putFloat(XposedPrefs.SPEED_ACCURACY, value) }

    // --- value modes (Off / Fixed / Auto) ---------------------------------

    private fun mode(key: String) = local.getInt(key, XposedPrefs.Defaults.MODE)
    private fun setMode(key: String, value: Int) = edit { putInt(key, value) }

    var modeAccuracy: Int
        get() = mode(XposedPrefs.MODE_ACCURACY)
        set(value) = setMode(XposedPrefs.MODE_ACCURACY, value)
    var modeAltitude: Int
        get() = mode(XposedPrefs.MODE_ALTITUDE)
        set(value) = setMode(XposedPrefs.MODE_ALTITUDE, value)
    var modeMsl: Int
        get() = mode(XposedPrefs.MODE_MSL)
        set(value) = setMode(XposedPrefs.MODE_MSL, value)
    var modeMslAccuracy: Int
        get() = mode(XposedPrefs.MODE_MSL_ACCURACY)
        set(value) = setMode(XposedPrefs.MODE_MSL_ACCURACY, value)
    var modeVerticalAccuracy: Int
        get() = mode(XposedPrefs.MODE_VERTICAL_ACCURACY)
        set(value) = setMode(XposedPrefs.MODE_VERTICAL_ACCURACY, value)
    var modeSpeed: Int
        get() = mode(XposedPrefs.MODE_SPEED)
        set(value) = setMode(XposedPrefs.MODE_SPEED, value)
    var modeSpeedAccuracy: Int
        get() = mode(XposedPrefs.MODE_SPEED_ACCURACY)
        set(value) = setMode(XposedPrefs.MODE_SPEED_ACCURACY, value)
    var modeBearing: Int
        get() = mode(XposedPrefs.MODE_BEARING)
        set(value) = setMode(XposedPrefs.MODE_BEARING, value)
    var modeBearingAccuracy: Int
        get() = mode(XposedPrefs.MODE_BEARING_ACCURACY)
        set(value) = setMode(XposedPrefs.MODE_BEARING_ACCURACY, value)
    var modeRandomize: Int
        get() = mode(XposedPrefs.MODE_RANDOMIZE)
        set(value) = setMode(XposedPrefs.MODE_RANDOMIZE, value)

    var bearing: Float
        get() = local.getFloat(XposedPrefs.BEARING, XposedPrefs.Defaults.BEARING)
        set(value) = edit { putFloat(XposedPrefs.BEARING, value) }
    var bearingAccuracy: Float
        get() = local.getFloat(XposedPrefs.BEARING_ACCURACY, XposedPrefs.Defaults.BEARING_ACCURACY)
        set(value) = edit { putFloat(XposedPrefs.BEARING_ACCURACY, value) }

    // --- targeting ---------------------------------------------------------

    var spoofAllScoped: Boolean
        // Same default as the hook side (Xshare) so a fresh install behaves consistently.
        get() = local.getBoolean(XposedPrefs.SPOOF_ALL_SCOPED, XposedPrefs.Defaults.SPOOF_ALL_SCOPED)
        set(value) = edit { putBoolean(XposedPrefs.SPOOF_ALL_SCOPED, value) }

    var targetApps: Set<String>
        get() = local.getString(XposedPrefs.TARGET_APPS, "")
            .orEmpty()
            .split(',')
            .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            .toSet()
        set(value) = edit { putString(XposedPrefs.TARGET_APPS, value.joinToString(",")) }

    /** Drops one package from the target list (used when an app is uninstalled). */
    fun removeTargetApp(pkg: String) {
        val current = targetApps
        if (pkg in current) targetApps = current - pkg
    }

    /**
     * Removes any target-list packages that are no longer installed. Called on app launch so a
     * stale entry cannot linger just because the Target Apps screen was never reopened.
     */
    fun pruneTargetApps() {
        val current = targetApps
        if (current.isEmpty()) return
        val installed = runCatching {
            gsApp.packageManager.getInstalledPackages(0).mapNotNull { it.packageName }.toHashSet()
        }.getOrNull() ?: return
        val kept = current.filterTo(mutableSetOf()) { it in installed }
        if (kept.size != current.size) targetApps = kept
    }

    // --- hooks -------------------------------------------------------------

    var blockGeofence: Boolean
        get() = local.getBoolean(XposedPrefs.BLOCK_GEOFENCE, XposedPrefs.Defaults.BLOCK_GEOFENCE)
        set(value) = edit { putBoolean(XposedPrefs.BLOCK_GEOFENCE, value) }

    var fakeGnss: Boolean
        get() = local.getBoolean(XposedPrefs.FAKE_GNSS, XposedPrefs.Defaults.FAKE_GNSS)
        set(value) = edit { putBoolean(XposedPrefs.FAKE_GNSS, value) }

    // --- wifi identity -----------------------------------------------------

    var wifiIdentityEnabled: Boolean
        get() = local.getBoolean(XposedPrefs.ENABLE_WIFI_IDENTITY, false)
        set(value) = edit { putBoolean(XposedPrefs.ENABLE_WIFI_IDENTITY, value) }
    var wifiSsid: String
        get() = local.getString(XposedPrefs.WIFI_SSID, XposedPrefs.Defaults.WIFI_SSID)!!
        set(value) = edit { putString(XposedPrefs.WIFI_SSID, value) }
    var wifiBssid: String
        get() = local.getString(XposedPrefs.WIFI_BSSID, XposedPrefs.Defaults.WIFI_BSSID)!!
        set(value) = edit { putString(XposedPrefs.WIFI_BSSID, value) }
    var wifiRssi: Int
        get() = local.getInt(XposedPrefs.WIFI_RSSI, XposedPrefs.Defaults.WIFI_RSSI)
        set(value) = edit { putInt(XposedPrefs.WIFI_RSSI, value) }

    // --- behaviour ---------------------------------------------------------

    var hideActiveToast: Boolean
        get() = local.getBoolean(XposedPrefs.HIDE_ACTIVE_TOAST, XposedPrefs.Defaults.HIDE_ACTIVE_TOAST)
        set(value) = edit { putBoolean(XposedPrefs.HIDE_ACTIVE_TOAST, value) }

    var enableBroadcastControl: Boolean
        get() = local.getBoolean(XposedPrefs.ENABLE_BROADCAST_CONTROL, false)
        set(value) = edit { putBoolean(XposedPrefs.ENABLE_BROADCAST_CONTROL, value) }

    var verboseLog: Boolean
        get() = local.getBoolean(XposedPrefs.VERBOSE_LOG, BuildConfig.DEBUG)
        set(value) = edit { putBoolean(XposedPrefs.VERBOSE_LOG, value) }

    var routeActive: Boolean
        get() = local.getBoolean(XposedPrefs.ROUTE_ACTIVE, false)
        set(value) = edit { putBoolean(XposedPrefs.ROUTE_ACTIVE, value) }

    /**
     * The constant speed (m/s) of the running route, or null when no route is driving movement.
     * While set, [update] reports this instead of a speed derived from consecutive points -- those
     * deltas are noisy at the navigation tick rate and made the reported speed jump around.
     */
    @Volatile
    var routeSpeedMs: Float? = null

    var languageTag: String
        get() = local.getString(XposedPrefs.LANGUAGE_TAG, "")!!
        set(value) = edit { putString(XposedPrefs.LANGUAGE_TAG, value) }

    /** Whether a pin has ever been placed -- drives showing the red marker on reopen. */
    var hasPin: Boolean
        get() = local.getBoolean(XposedPrefs.PIN_PLACED, false)
        set(value) = edit { putBoolean(XposedPrefs.PIN_PLACED, value) }

    // Previous position, used to derive speed and heading. Every position update in the app --
    // map taps, the joystick, and route navigation -- funnels through update(), so this is the
    // only place that needs to track movement.
    @Volatile private var elevationLat: Double? = null
    @Volatile private var elevationLng: Double? = null

    @Volatile private var lastLat: Double? = null
    @Volatile private var lastLng: Double? = null
    @Volatile private var lastAt: Long = 0L

    /**
     * System hooks are now on by default (they proved stable and are the useful path). Turn them on
     * once for existing installs -- including ones an earlier build had forced off -- so upgraders
     * get the new default. The toggle stays, so anyone can turn it back off as a recovery valve.
     */
    fun migrateOnce() {
        if (local.getBoolean(MIGRATED_SYSTEM_HOOK_ON, false)) return
        edit {
            putBoolean(XposedPrefs.HOOKED_SYSTEM, true)
            putBoolean(MIGRATED_SYSTEM_HOOK_ON, true)
        }
    }

    private const val MIGRATED_SYSTEM_HOOK_ON = "migrated_system_hook_on"

    /** True once the system-hooks warning has been shown; it is a one-time note, not every enable. */
    var systemHooksWarned: Boolean
        get() = local.getBoolean(SYSTEM_HOOKS_WARNED, false)
        set(value) = edit { putBoolean(SYSTEM_HOOKS_WARNED, value) }

    private const val SYSTEM_HOOKS_WARNED = "system_hooks_warned"

    /**
     * Keys that a settings reset must NOT touch. Saved favourites live in the Room database, not
     * here, so they are safe automatically; these are the prefs-backed things a reset should keep:
     * the target-apps list, the current position, and the runtime movement/state flags -- resetting
     * settings should not lose the user's scoped apps, silently stop spoofing, or drop the pin.
     */
    private val RESET_PRESERVED_KEYS = setOf(
        XposedPrefs.TARGET_APPS,
        XposedPrefs.LATITUDE, XposedPrefs.LONGITUDE, XposedPrefs.START,
        XposedPrefs.PIN_PLACED,
        XposedPrefs.LIVE_SPEED, XposedPrefs.LIVE_BEARING, XposedPrefs.LIVE_MOVING_UNTIL,
        XposedPrefs.TERRAIN_ELEVATION,
    )

    /**
     * Returns every setting to its default by clearing all keys except the ones in
     * [RESET_PRESERVED_KEYS]. Using a blocklist (rather than listing every setting to remove) means
     * a newly added setting is reset automatically instead of being silently missed.
     */
    fun resetToDefaults() {
        val keys = local.all.keys.toList()
        edit {
            keys.forEach { key ->
                if (key !in RESET_PRESERVED_KEYS) remove(key)
            }
        }
    }

    fun update(start: Boolean, la: Double, ln: Double) {
        val now = System.currentTimeMillis()
        val prevLat = lastLat
        val prevLng = lastLng
        val elapsed = now - lastAt

        // Only treat it as movement if the previous point is recent enough to be part of the same
        // motion. A stale point would produce an absurd speed.
        val moving = start && prevLat != null && prevLng != null &&
            elapsed in 1..XposedPrefs.Defaults.MOVING_WINDOW_MS

        var speedMs = 0f
        var bearingDeg = 0f
        if (moving) {
            val distance = haversineMetres(prevLat!!, prevLng!!, la, ln)
            speedMs = (distance / (elapsed / 1000.0)).toFloat()
            bearingDeg = initialBearing(prevLat, prevLng, la, ln).toFloat()
        }

        lastLat = la
        lastLng = ln
        lastAt = now

        maybeRefreshElevation(la, ln)

        runInBackground {
            edit {
                putFloat(XposedPrefs.LATITUDE, la.toFloat())
                putFloat(XposedPrefs.LONGITUDE, ln.toFloat())
                putBoolean(XposedPrefs.START, start)
                // A pin now exists at this position; remember it so the marker returns on reopen
                // even after spoofing is stopped.
                putBoolean(XposedPrefs.PIN_PLACED, true)
                val routeSpeed = routeSpeedMs
                if (routeSpeed != null) {
                    // A route is driving movement: report its constant speed (stable), with the
                    // heading from the last step when we have one.
                    putFloat(XposedPrefs.LIVE_SPEED, routeSpeed)
                    if (moving) putFloat(XposedPrefs.LIVE_BEARING, bearingDeg)
                    putLong(
                        XposedPrefs.LIVE_MOVING_UNTIL,
                        now + XposedPrefs.Defaults.MOVING_WINDOW_MS
                    )
                } else if (moving) {
                    putFloat(XposedPrefs.LIVE_SPEED, speedMs)
                    putFloat(XposedPrefs.LIVE_BEARING, bearingDeg)
                    // The hook treats the fix as moving until this timestamp passes, so movement
                    // decays on its own when updates stop -- no "stop moving" call needed.
                    putLong(
                        XposedPrefs.LIVE_MOVING_UNTIL,
                        now + XposedPrefs.Defaults.MOVING_WINDOW_MS
                    )
                } else {
                    putLong(XposedPrefs.LIVE_MOVING_UNTIL, 0L)
                }
            }
        }
    }

    /**
     * Fetches terrain elevation for the position that is already set, if we have none cached.
     *
     * Without this, a location pinned before this feature existed -- or simply set in a previous
     * run -- would leave Auto altitude falling back to a generic value instead of real terrain,
     * because the lookup otherwise only happens when the pin moves.
     */
    fun refreshElevationOnStart() {
        if (!isStarted) return
        // The two values are fetched independently: elevation may already be cached from an
        // earlier run while the offset has never been looked up, and checking only the former
        // would leave the offset permanently missing.
        if (!local.contains(XposedPrefs.TERRAIN_ELEVATION)) {
            maybeRefreshElevation(getLat, getLng)
        } else if (!local.contains(XposedPrefs.GEOID_OFFSET)) {
            runInBackground {
                ElevationService.lookupGeoidOffset(getLat, getLng)?.let {
                    edit { putFloat(XposedPrefs.GEOID_OFFSET, it) }
                }
            }
        }
    }

    /**
     * Fetches the real ground elevation when the pin has moved far enough to matter.
     *
     * Only refreshed beyond ~200 m: terrain does not change meaningfully below that, and the
     * joystick and route navigation call update() many times a second, which would otherwise mean
     * a network request per frame.
     */
    private fun maybeRefreshElevation(la: Double, ln: Double) {
        val prevLat = elevationLat
        val prevLng = elevationLng
        val needsRefresh = prevLat == null || prevLng == null ||
            haversineMetres(prevLat, prevLng, la, ln) > 200.0
        if (!needsRefresh) return

        elevationLat = la
        elevationLng = ln
        runInBackground {
            val elevation = ElevationService.lookup(la, ln)
            if (elevation != null) {
                edit { putFloat(XposedPrefs.TERRAIN_ELEVATION, elevation) }

                // Re-check the stored geoid offset against the service and correct it if it has
                // drifted, rather than trusting a value written once and never revisited.
                val offset = ElevationService.lookupGeoidOffset(la, ln)
                if (offset != null) {
                    val stored: Float = local.getFloat(XposedPrefs.GEOID_OFFSET, Float.NaN)
                    if (stored.isNaN() || abs(stored - offset) > 0.5f) {
                        edit { putFloat(XposedPrefs.GEOID_OFFSET, offset) }
                    }
                }
            } else {
                // Leave any previous value in place rather than clearing it; a stale nearby
                // elevation beats no elevation at all.
                elevationLat = prevLat
                elevationLng = prevLng
            }
        }
    }

    /** Great-circle distance in metres. */
    private fun haversineMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial bearing in degrees, normalised to 0..360. */
    private fun initialBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun runInBackground(method: suspend () -> Unit) {
        GlobalScope.launch(Dispatchers.IO) {
            method.invoke()
        }
    }
}

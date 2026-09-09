package io.github.jqssun.gpssetter.xposed

import android.content.SharedPreferences
import io.github.jqssun.gpssetter.BuildConfig

/**
 * Read-only view of the module settings as seen from inside a hooked process.
 *
 * The modern Xposed API has no `XSharedPreferences`; settings arrive through the framework's
 * remote preferences, which stay live (the framework pushes updates), so there is no `reload()`
 * to call. [prefs] is null when the framework does not advertise `PROP_CAP_REMOTE`, in which case
 * every setting falls back to its default and [isStarted] is false, i.e. no spoofing happens.
 */
class Xshare(private val prefs: SharedPreferences?) {

    private fun bool(key: String, def: Boolean) = prefs?.getBoolean(key, def) ?: def
    private fun float(key: String, def: Float) = prefs?.getFloat(key, def) ?: def
    private fun int(key: String, def: Int) = prefs?.getInt(key, def) ?: def
    private fun str(key: String, def: String) = prefs?.getString(key, def) ?: def

    // --- position ---------------------------------------------------------

    val isStarted: Boolean get() = bool(XposedPrefs.START, false)
    val getLat: Double get() = float(XposedPrefs.LATITUDE, 45.0f).toDouble()
    val getLng: Double get() = float(XposedPrefs.LONGITUDE, 0.0f).toDouble()

    val accuracy: String? get() = str(XposedPrefs.ACCURACY_SETTING, XposedPrefs.Defaults.ACCURACY)
    /**
     * How far the reported point may wander from the pin, or null for none.
     *
     * Auto uses a few metres of jitter, which is what a stationary real device shows; a position
     * that is byte-identical on every read is not something GNSS produces.
     */
    val effectiveRandomizeRadius: Float?
        get() = when (mode(XposedPrefs.MODE_RANDOMIZE)) {
            XposedPrefs.Mode.FIXED ->
                float(XposedPrefs.RANDOMIZE_RADIUS, XposedPrefs.Defaults.RANDOMIZE_RADIUS)
            XposedPrefs.Mode.AUTO -> DynamicValues.randomizeRadius(getLat, getLng)
            else -> null
        }

    // --- optional fields --------------------------------------------------
    // Each field is Off, Fixed or Auto. Off keeps whatever the original fix carried rather than
    // forcing a zero -- a location reporting altitude 0.0 and speed 0.0 is itself a tell.

    private fun mode(key: String) = int(key, XposedPrefs.Defaults.MODE)

    /** Terrain elevation for the pinned point, when the app managed to look one up. */
    private val terrainHint: Float?
        get() = prefs?.getFloat(XposedPrefs.TERRAIN_ELEVATION, Float.NaN)
            ?.takeIf { !it.isNaN() }

    /** Real offset between the two height figures, when it has been looked up. */
    private val geoidHint: Float?
        get() = prefs?.getFloat(XposedPrefs.GEOID_OFFSET, Float.NaN)
            ?.takeIf { !it.isNaN() }

    /** Horizontal accuracy. Unlike the others this has always been on, so Fixed is the default. */
    val effectiveAccuracy: Float
        get() = when (mode(XposedPrefs.MODE_ACCURACY)) {
            XposedPrefs.Mode.AUTO -> DynamicValues.accuracy(getLat, getLng)
            else -> accuracy?.toFloatOrNull() ?: 10f
        }

    val effectiveAltitude: Double?
        get() = when (mode(XposedPrefs.MODE_ALTITUDE)) {
            XposedPrefs.Mode.FIXED -> float(XposedPrefs.ALTITUDE, XposedPrefs.Defaults.ALTITUDE).toDouble()
            XposedPrefs.Mode.AUTO -> DynamicValues.altitude(getLat, getLng, terrainHint, geoidHint)
            else -> null
        }

    val effectiveMsl: Float?
        get() = when (mode(XposedPrefs.MODE_MSL)) {
            XposedPrefs.Mode.FIXED -> float(XposedPrefs.MSL, XposedPrefs.Defaults.MSL)
            XposedPrefs.Mode.AUTO -> DynamicValues.meanSeaLevel(getLat, getLng, terrainHint)
            else -> null
        }

    val effectiveMslAccuracy: Float?
        get() = when (mode(XposedPrefs.MODE_MSL_ACCURACY)) {
            XposedPrefs.Mode.FIXED -> float(XposedPrefs.MSL_ACCURACY, XposedPrefs.Defaults.MSL_ACCURACY)
            XposedPrefs.Mode.AUTO -> DynamicValues.meanSeaLevelAccuracy(getLat, getLng)
            else -> null
        }

    val effectiveVerticalAccuracy: Float?
        get() = when (mode(XposedPrefs.MODE_VERTICAL_ACCURACY)) {
            XposedPrefs.Mode.FIXED ->
                float(XposedPrefs.VERTICAL_ACCURACY, XposedPrefs.Defaults.VERTICAL_ACCURACY)
            XposedPrefs.Mode.AUTO -> DynamicValues.verticalAccuracy(getLat, getLng)
            else -> null
        }

    // --- live movement ----------------------------------------------------

    /** True while the app is actively driving the position (joystick or route navigation). */
    val isMoving: Boolean
        get() = prefs?.let {
            it.getLong(XposedPrefs.LIVE_MOVING_UNTIL, 0L) > System.currentTimeMillis()
        } ?: false

    /**
     * Speed to report. Live movement always wins -- a device travelling a route while reporting a
     * static speed contradicts itself. Otherwise it follows the configured mode.
     */
    /** True while an auto-route is running; the route's own speed then overrides the settings. */
    val isRouteActive: Boolean get() = bool(XposedPrefs.ROUTE_ACTIVE, false)

    val effectiveSpeed: Float?
        get() = when {
            // A running route drops the app's speed setting and reports the speed it is actually
            // travelling at, until the route ends.
            isRouteActive -> float(XposedPrefs.LIVE_SPEED, 0f)
            else -> effectiveSpeedFromSettings
        }

    private val effectiveSpeedFromSettings: Float?
        get() = when (mode(XposedPrefs.MODE_SPEED)) {
            // Fixed means frozen: the configured value wins even while the pin is moving, so
            // setting 0 really does report a stationary device throughout a route.
            XposedPrefs.Mode.FIXED -> float(XposedPrefs.SPEED, XposedPrefs.Defaults.SPEED)
            // Auto follows reality: the computed speed while moving, genuinely 0 when parked.
            XposedPrefs.Mode.AUTO ->
                if (isMoving) float(XposedPrefs.LIVE_SPEED, 0f) else 0f
            else -> null
        }

    val effectiveSpeedAccuracy: Float?
        get() = when (mode(XposedPrefs.MODE_SPEED_ACCURACY)) {
            XposedPrefs.Mode.FIXED ->
                float(XposedPrefs.SPEED_ACCURACY, XposedPrefs.Defaults.SPEED_ACCURACY)
            XposedPrefs.Mode.AUTO ->
                DynamicValues.speedAccuracy(effectiveSpeed ?: 0f, getLat, getLng)
            else -> null
        }

    /**
     * Heading in degrees.
     *
     * Auto keeps reporting the last travelled heading for a short while after movement stops,
     * rather than snapping to zero -- real receivers derive heading from displacement and it goes
     * stale rather than resetting.
     */
    val effectiveBearing: Float?
        get() {
            // A running route drives the heading and overrides the bearing setting -- the same as
            // speed. Reporting a fixed heading while travelling a route contradicts the movement.
            if (isRouteActive && isMoving) return float(XposedPrefs.LIVE_BEARING, 0f)
            return when (mode(XposedPrefs.MODE_BEARING)) {
                XposedPrefs.Mode.FIXED -> float(XposedPrefs.BEARING, XposedPrefs.Defaults.BEARING)
                XposedPrefs.Mode.AUTO -> when {
                    isMoving -> float(XposedPrefs.LIVE_BEARING, 0f)
                    bearingStillFresh -> float(XposedPrefs.LIVE_BEARING, 0f)
                    else -> null
                }
                else -> null
            }
        }

    /** True for a minute after movement stops, so heading decays instead of vanishing. */
    private val bearingStillFresh: Boolean
        get() = prefs?.let {
            val until = it.getLong(XposedPrefs.LIVE_MOVING_UNTIL, 0L)
            until > 0 && System.currentTimeMillis() - until < 60_000L
        } ?: false

    val effectiveBearingAccuracy: Float?
        get() = when (mode(XposedPrefs.MODE_BEARING_ACCURACY)) {
            XposedPrefs.Mode.FIXED ->
                float(XposedPrefs.BEARING_ACCURACY, XposedPrefs.Defaults.BEARING_ACCURACY)
            XposedPrefs.Mode.AUTO ->
                // Only meaningful alongside a bearing; without one it would be describing nothing.
                effectiveBearing?.let {
                    DynamicValues.bearingAccuracy(effectiveSpeed ?: 0f, getLat, getLng)
                }
            else -> null
        }

    // --- targeting --------------------------------------------------------

    val spoofAllScoped: Boolean
        get() = bool(XposedPrefs.SPOOF_ALL_SCOPED, XposedPrefs.Defaults.SPOOF_ALL_SCOPED)

    private val targetApps: Set<String>
        get() = str(XposedPrefs.TARGET_APPS, "")
            .split(',')
            .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            .toSet()

    /**
     * Whether [packageName] should receive a fake location. With [spoofAllScoped] on this is every
     * app; otherwise only those ticked in the target apps picker.
     */
    fun shouldSpoof(packageName: String): Boolean =
        isStarted && (spoofAllScoped || packageName in targetApps)

    // --- hooks ------------------------------------------------------------

    val isHookedSystem: Boolean
        get() = bool(XposedPrefs.HOOKED_SYSTEM, XposedPrefs.Defaults.HOOKED_SYSTEM)
    val blockGeofence: Boolean
        get() = bool(XposedPrefs.BLOCK_GEOFENCE, XposedPrefs.Defaults.BLOCK_GEOFENCE)
    val fakeGnss: Boolean
        get() = bool(XposedPrefs.FAKE_GNSS, XposedPrefs.Defaults.FAKE_GNSS)

    // --- wifi identity ----------------------------------------------------

    val wifiIdentityEnabled: Boolean get() = bool(XposedPrefs.ENABLE_WIFI_IDENTITY, false)
    val wifiSsid: String get() = str(XposedPrefs.WIFI_SSID, XposedPrefs.Defaults.WIFI_SSID)
    val wifiBssid: String get() = str(XposedPrefs.WIFI_BSSID, XposedPrefs.Defaults.WIFI_BSSID)
    val wifiRssi: Int get() = int(XposedPrefs.WIFI_RSSI, XposedPrefs.Defaults.WIFI_RSSI)

    // --- behaviour --------------------------------------------------------

    val hideActiveToast: Boolean get() = bool(XposedPrefs.HIDE_ACTIVE_TOAST, false)

    /**
     * Whether per-fix logging is on. Very chatty inside hooked apps, so it defaults on only for
     * debug builds; release builds stay quiet unless explicitly switched on in settings.
     */
    val isVerboseLogging: Boolean
        get() = bool(XposedPrefs.VERBOSE_LOG, BuildConfig.DEBUG)

    /** One-line summary of the live settings, for the startup log. */
    fun describe(): String =
        if (prefs == null) "UNAVAILABLE (framework has no remote preference support)"
        else "started=$isStarted lat=$getLat lng=$getLng acc=$effectiveAccuracy " +
            "jitter=${effectiveRandomizeRadius ?: "-"} " +
            "alt=${effectiveAltitude ?: "-"} bearing=${effectiveBearing ?: "-"} " +
            "speed=${effectiveSpeed ?: "-"} moving=$isMoving " +
            "targets=${if (spoofAllScoped) "ALL" else targetApps.size.toString()} " +
            "systemHooks=$isHookedSystem gnss=$fakeGnss wifi=$wifiIdentityEnabled verbose=$isVerboseLogging"
}

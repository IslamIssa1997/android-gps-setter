package io.github.jqssun.gpssetter.control

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import io.github.jqssun.gpssetter.utils.PrefManager
import timber.log.Timber

/**
 * Lets other apps, scripts or ADB drive the module without opening the UI.
 *
 * Declared disabled in the manifest and switched on only when the user enables it in settings, so
 * it is inert by default. Once enabled it is deliberately unguarded, per the user's choice, which
 * means any app on the device that knows the action names can move the reported location.
 *
 * Example:
 *     adb shell am broadcast -a com.islam.gps.action.SET_LOCATION \
 *         --ef latitude 21.4858 --ef longitude 39.1925
 */
class ControlReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_START = "com.islam.gps.action.START"
        const val ACTION_STOP = "com.islam.gps.action.STOP"
        const val ACTION_SET_LOCATION = "com.islam.gps.action.SET_LOCATION"

        const val EXTRA_LATITUDE = "latitude"
        const val EXTRA_LONGITUDE = "longitude"

        /** Reflects the settings toggle onto the manifest component. */
        fun setEnabled(context: Context, enabled: Boolean) {
            val state = if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            runCatching {
                context.packageManager.setComponentEnabledSetting(
                    ComponentName(context, ControlReceiver::class.java),
                    state,
                    PackageManager.DONT_KILL_APP,
                )
            }.onFailure { Timber.w(it, "could not toggle ControlReceiver") }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Defence in depth: the component should already be disabled, but a stale enabled state
        // must not outlive the setting.
        if (!PrefManager.enableBroadcastControl) {
            Timber.w("ignoring %s - external control is disabled", intent.action)
            return
        }

        when (intent.action) {
            ACTION_START -> PrefManager.update(true, PrefManager.getLat, PrefManager.getLng)
            ACTION_STOP -> PrefManager.update(false, PrefManager.getLat, PrefManager.getLng)
            ACTION_SET_LOCATION -> handleSetLocation(intent)
            else -> Timber.w("unknown action %s", intent.action)
        }
    }

    private fun handleSetLocation(intent: Intent) {
        val lat = intent.coordExtra(EXTRA_LATITUDE)
        val lng = intent.coordExtra(EXTRA_LONGITUDE)

        // Out-of-range coordinates would be written straight through to the hook and produce
        // nonsense fixes, so reject rather than clamp.
        if (lat == null || lng == null || lat !in -90.0..90.0 || lng !in -180.0..180.0) {
            Timber.w("rejected SET_LOCATION with lat=%s lng=%s", lat, lng)
            return
        }
        Timber.i("SET_LOCATION -> %f, %f", lat, lng)
        PrefManager.update(true, lat, lng)
    }

    /** Reads a coordinate whether it arrived as a string (--es), double (--ed), or float (--ef) extra. */
    private fun Intent.coordExtra(key: String): Double? {
        if (!hasExtra(key)) return null
        getStringExtra(key)?.toDoubleOrNull()?.let { return it }
        getDoubleExtra(key, Double.NaN).takeIf { !it.isNaN() }?.let { return it }
        getFloatExtra(key, Float.NaN).takeIf { !it.isNaN() }?.let { return it.toDouble() }
        return null
    }
}

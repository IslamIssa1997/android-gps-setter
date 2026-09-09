package io.github.jqssun.gpssetter.xposed

import android.location.GnssStatus
import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/**
 * Builds a plausible, self-consistent [GnssStatus] for a spoofed position.
 *
 * Shared by the app-side hook (delivers to the app's `GnssStatus.Callback`) and the system-side
 * hook (delivers to the `IGnssStatusListener` binder in system_server), so the constellation logic
 * lives in exactly one place. The geometry is derived from the position and a slow time bucket, so
 * the set of satellites stays stable between reads and drifts gradually rather than jumping.
 */
object SyntheticGnss {

    private val CONSTELLATIONS = intArrayOf(
        GnssStatus.CONSTELLATION_GPS,
        GnssStatus.CONSTELLATION_GLONASS,
        GnssStatus.CONSTELLATION_GALILEO,
        GnssStatus.CONSTELLATION_BEIDOU,
    )

    /** Stable pseudo-random seed derived from the spoofed position. */
    private fun seed(lat: Double, lng: Double): Int =
        (floor(lat * 1000).toInt() * 31 + floor(lng * 1000).toInt()) and 0x7fffffff

    /**
     * @return a synthetic [GnssStatus] for [lat]/[lng], or null when the hidden builder is
     *   unavailable on this build (One UI has been known to diverge) -- callers then fall back to
     *   suppression only.
     */
    fun build(lat: Double, lng: Double): GnssStatus? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            val builderClass = Class.forName("android.location.GnssStatus\$Builder")
            val builder = builderClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()

            // addSatellite is a hidden method whose exact parameter list varies by Android version
            // and vendor. Find the real one and fit arguments to it: the first five are always
            // constellation/svid/cn0/elevation/azimuth, the rest filled with defaults by type.
            val addMethod = builderClass.declaredMethods
                .filter { it.name == "addSatellite" }
                .maxByOrNull { it.parameterCount } ?: return@runCatching null
            val types = addMethod.parameterTypes

            val bucket = System.currentTimeMillis() / 30_000.0
            val count = 9 + (abs(seed(lat, lng)) % 6) // 9..14 satellites in view
            for (i in 0 until count) {
                val phase = bucket * 0.05 + i * 0.7 + seed(lat, lng) * 0.001
                val constellation = CONSTELLATIONS[i % CONSTELLATIONS.size]
                val svid = 1 + ((i * 7 + abs(seed(lat, lng))) % 32)
                val elevation = (15 + 32 * (1 + sin(phase))).toFloat().coerceIn(15f, 80f)
                val cn0 = (18 + elevation * 0.35f + (svid % 5)).coerceIn(12f, 45f)
                val azimuth = ((i * 360.0 / count) + bucket * 0.4).mod(360.0).toFloat()

                val args = arrayOfNulls<Any?>(types.size)
                args[0] = constellation
                args[1] = svid
                args[2] = cn0
                args[3] = elevation
                args[4] = azimuth
                for (j in 5 until types.size) {
                    args[j] = if (types[j] == Float::class.javaPrimitiveType) 0f else false
                }
                if (types.size > 5) args[5] = true              // hasEphemeris
                if (types.size > 6) args[6] = true              // hasAlmanac
                if (types.size > 7) args[7] = elevation > 25f   // usedInFix
                HiddenApiBypass.invoke(builderClass, builder, "addSatellite", *args)
            }
            HiddenApiBypass.invoke(builderClass, builder, "build") as? GnssStatus
        }.getOrNull()
    }
}

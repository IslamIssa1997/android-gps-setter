package io.github.jqssun.gpssetter.xposed

import android.location.GnssStatus
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.libxposed.api.XposedInterface
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/**
 * Suppresses the device's real satellite data and, when enabled, substitutes a plausible
 * constellation consistent with the spoofed position.
 *
 * Blocking alone is itself a signal: an app that receives a valid fix while `GnssStatus` reports
 * no satellites can infer that something is intercepting. Apps cross-check satellite count,
 * constellation mix, per-satellite signal strength and whether the azimuth/elevation geometry is
 * physically consistent with the reported coordinates.
 */
class GnssHooks(
    private val module: XposedInterface,
    private val settings: Xshare,
    private val packageName: String,
) {
    private companion object {
        const val TAG = "GPS Setter"

        /** Callback registration methods whose real data must never reach the app. */
        val SUPPRESSED = setOf(
            "registerGnssStatusCallback",
            "addGnssMeasurementsListener",
            "registerGnssMeasurementsCallback",
            "addGnssNavigationMessageListener",
            "registerGnssNavigationMessageCallback",
            "addGnssAntennaInfoListener",
            "registerAntennaInfoListener",
        )

        val CONSTELLATIONS = intArrayOf(
            GnssStatus.CONSTELLATION_GPS,
            GnssStatus.CONSTELLATION_GLONASS,
            GnssStatus.CONSTELLATION_GALILEO,
            GnssStatus.CONSTELLATION_BEIDOU,
        )
    }

    /** A running feed: the repeating tick, and how to deliver onto the app's own thread. */
    private class Feed(val tick: Runnable, val deliver: (() -> Unit) -> Unit)

    /** The satellite-status callbacks we are feeding, each with the loop that keeps it fed. */
    private val activeFeeds = ConcurrentHashMap<Any, Feed>()
    private val handler = Handler(Looper.getMainLooper())

    fun install() {
        var suppressed = 0
        for (method in LocationManager::class.java.declaredMethods) {
            when (method.name) {
                // Satellite status is the one the synthetic constellation is delivered through:
                // suppress the real receiver, then feed the app our own GnssStatus instead.
                "registerGnssStatusCallback" -> {
                    module.hook(method)
                        .setExceptionMode(hookMode(settings.isVerboseLogging))
                        .intercept { chain ->
                            if (!settings.shouldSpoof(packageName)) return@intercept chain.proceed()
                            startFeed(chain.args.toList())
                            when (method.returnType) {
                                Boolean::class.javaPrimitiveType -> true
                                else -> null
                            }
                        }
                    suppressed++
                }
                "unregisterGnssStatusCallback" -> {
                    module.hook(method)
                        .setExceptionMode(hookMode(settings.isVerboseLogging))
                        .intercept { chain ->
                            stopFeed(chain.args.toList())
                            chain.proceed()
                        }
                }
                in SUPPRESSED -> {
                    module.hook(method)
                        .setExceptionMode(hookMode(settings.isVerboseLogging))
                        .intercept { chain ->
                            if (!settings.shouldSpoof(packageName)) return@intercept chain.proceed()
                            // Registration is accepted but never wired to the real receiver.
                            // Boolean methods must claim success or the app may treat GNSS as
                            // unavailable, which is itself unusual for a device reporting a fix.
                            when (method.returnType) {
                                Boolean::class.javaPrimitiveType -> true
                                else -> null
                            }
                        }
                    suppressed++
                }
            }
        }
        module.log(
            Log.INFO, TAG,
            "gnss: suppressed $suppressed registration points in $packageName" +
                if (settings.fakeGnss) " (synthetic constellation on)" else " (block only)"
        )
    }

    /**
     * Starts feeding a synthetic [GnssStatus] to the app's satellite-status callback.
     *
     * The callback is [GnssStatus.Callback]; its methods are public, so they are called directly on
     * the main looper. When synthesis is unavailable (older platform, hidden builder missing) no
     * feed starts and the app simply sees a receiver with no satellites -- the block-only fallback.
     */
    private fun startFeed(args: List<Any?>) {
        if (!settings.fakeGnss) return
        val callback = args.firstOrNull { it is GnssStatus.Callback } as? GnssStatus.Callback ?: return
        if (activeFeeds.containsKey(callback)) return

        // The app registers with either a Handler or an Executor, and the platform delivers the
        // callback on THAT thread. Apps (Google Maps included) assert they are on it, so we must
        // deliver there too -- calling on our own main thread throws "Should be running on
        // LOCATION_SENSORS". Route every callback invocation through the app's own executor/handler.
        val executor = args.firstOrNull { it is java.util.concurrent.Executor } as? java.util.concurrent.Executor
        val cbHandler = args.firstOrNull { it is Handler } as? Handler
        val deliver: (() -> Unit) -> Unit = { block ->
            when {
                executor != null -> runCatching { executor.execute { runCatching { block() }.onFailure { logTick(it) } } }
                cbHandler != null -> runCatching { cbHandler.post { runCatching { block() }.onFailure { logTick(it) } } }
                else -> runCatching { block() }.onFailure { logTick(it) }
            }
        }

        // Claim the receiver started, then keep pushing an updated constellation. A satellite set
        // that never changes is as suspicious as one that changes every second, so it drifts.
        deliver { callback.onStarted() }
        var first = true
        val tick = object : Runnable {
            override fun run() {
                if (!activeFeeds.containsKey(callback)) return
                val status = runCatching { buildSyntheticStatus() as? GnssStatus }.getOrNull()
                if (status != null) {
                    val isFirst = first
                    first = false
                    deliver {
                        if (isFirst) callback.onFirstFix(1200)
                        callback.onSatelliteStatusChanged(status)
                    }
                }
                handler.postDelayed(this, 1000L)
            }
        }
        activeFeeds[callback] = Feed(tick, deliver)
        handler.post(tick)
        module.log(Log.INFO, TAG, "gnss: feeding synthetic constellation to $packageName")
    }

    private fun logTick(t: Throwable) = module.log(Log.WARN, TAG, "gnss feed tick failed: $t")

    /** Stops the feed for whichever callback is being unregistered. */
    private fun stopFeed(args: List<Any?>) {
        val callback = args.firstOrNull { it is GnssStatus.Callback } ?: return
        activeFeeds.remove(callback)?.let { feed ->
            handler.removeCallbacks(feed.tick)
            feed.deliver { (callback as? GnssStatus.Callback)?.onStopped() }
        }
    }

    /**
     * Builds a synthetic [GnssStatus] for the current spoofed position.
     *
     * The geometry is derived deterministically from the position and a slowly advancing time
     * bucket, so the constellation stays stable between reads and drifts gradually. A set of
     * satellites that jumps around every second is as suspicious as one that never moves.
     *
     * Returns null when the platform's hidden builder is unavailable -- One UI has been known to
     * diverge here -- in which case the caller falls back to suppression only.
     */
    fun buildSyntheticStatus(): Any? {
        if (!settings.fakeGnss) return null
        return SyntheticGnss.build(settings.getLat, settings.getLng)
            ?: run {
                module.log(Log.WARN, TAG, "synthetic GnssStatus unavailable in $packageName, blocking only")
                null
            }
    }
}

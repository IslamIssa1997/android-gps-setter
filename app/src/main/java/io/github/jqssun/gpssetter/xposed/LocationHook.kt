package io.github.jqssun.gpssetter.xposed

import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.util.Log
import io.github.libxposed.api.XposedInterface
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Method
import java.util.Random
import kotlin.math.cos

/**
 * Installs the location hooks through the modern Xposed API.
 *
 * Compared with the legacy `XC_MethodHook` version, an interceptor either returns a value of its
 * own (which short-circuits the rest of the chain and the original method, the equivalent of the
 * old `param.result = ...`) or calls `chain.proceed(...)` to continue, optionally with rewritten
 * arguments (the equivalent of the old `param.args[0] = ...`).
 */
class LocationHook(
    private val module: XposedInterface,
    private val settings: Xshare,
) {

    private companion object {
        const val TAG = "GPS Setter"
        const val PI = 3.14159265359
        const val EARTH = 6378137.0

        /** Refresh interval for the spoofed fix, in milliseconds. */
        const val APP_INTERVAL = 80L
        const val SYSTEM_INTERVAL = 200L
    }

    @Volatile
    private var newlat: Double = 45.0000

    @Volatile
    private var newlng: Double = 0.0000

    @Volatile
    private var accuracy: Float = 0.0f

    @Volatile
    private var mLastUpdated: Long = 0

    private val rand = Random()

    private fun updateLocation(packageName: String) {
        try {
            mLastUpdated = System.currentTimeMillis()
            val radius = settings.effectiveRandomizeRadius
            if (radius == null) {
                newlat = settings.getLat
                newlng = settings.getLng
            } else {
                val x = (rand.nextDouble() * 2 - 1) * radius
                val y = (rand.nextDouble() * 2 - 1) * radius
                val dlat = x / EARTH
                val dlng = y / (EARTH * cos(PI * settings.getLat / 180.0))
                newlat = settings.getLat + (dlat * 180.0 / PI)
                newlng = settings.getLng + (dlng * 180.0 / PI)
            }
            accuracy = settings.effectiveAccuracy
        } catch (e: Exception) {
            module.log(Log.ERROR, TAG, "Failed to read module settings for $packageName", e)
        }
    }

    private fun refresh(packageName: String, interval: Long) {
        if (System.currentTimeMillis() - mLastUpdated > interval) {
            updateLocation(packageName)
        }
    }

    /** Builds a fresh fake fix, keeping whatever metadata [origin] carries. */
    private fun fakeLocation(origin: Location?): Location {
        val location: Location
        if (origin == null) {
            location = Location(LocationManager.GPS_PROVIDER)
            location.time = System.currentTimeMillis() - 300
        } else {
            location = Location(origin.provider)
            location.time = origin.time
            location.accuracy = accuracy
            location.bearing = origin.bearing
            location.bearingAccuracyDegrees = origin.bearingAccuracyDegrees
            location.elapsedRealtimeNanos = origin.elapsedRealtimeNanos
            location.verticalAccuracyMeters = origin.verticalAccuracyMeters
            // Copy the movement/height fields too, so that when their mode is Off the fake fix
            // keeps the original value instead of reporting a suspicious 0.0. applyOptionalFields()
            // still overrides any field whose mode is Fixed or Auto.
            location.speed = origin.speed
            location.altitude = origin.altitude
            location.speedAccuracyMetersPerSecond = origin.speedAccuracyMetersPerSecond
            if (Build.VERSION.SDK_INT >= 34 && origin.hasMslAltitude()) {
                location.mslAltitudeMeters = origin.mslAltitudeMeters
            }
        }

        location.latitude = newlat
        location.longitude = newlng
        applyOptionalFields(location)
        if (settings.isVerboseLogging) {
            module.log(Log.DEBUG, TAG, "fix -> ${location.latitude}, ${location.longitude}" +
                " acc=${location.accuracy} provider=${location.provider}")
        }
        try {
            HiddenApiBypass.invoke(
                location.javaClass, location, "setIsFromMockProvider", false
            )
        } catch (e: Exception) {
            module.log(Log.WARN, TAG, "unable to clear the mock provider flag", e)
        }
        return location
    }

    /**
     * Applies altitude, speed, bearing and the various accuracies, each only when its setting is
     * enabled. Fields left disabled keep whatever the original fix carried rather than being
     * forced to zero -- a location reporting altitude 0.0 and speed 0.0 is itself a tell.
     */
    private fun applyOptionalFields(location: Location) {
        settings.effectiveAltitude?.let { location.altitude = it }
        settings.effectiveSpeed?.let { location.speed = it }
        settings.effectiveBearing?.let { location.bearing = it }
        settings.effectiveSpeedAccuracy?.let { location.speedAccuracyMetersPerSecond = it }
        settings.effectiveVerticalAccuracy?.let { location.verticalAccuracyMeters = it }
        settings.effectiveBearingAccuracy?.let { location.bearingAccuracyDegrees = it }

        if (Build.VERSION.SDK_INT >= 34) {
            settings.effectiveMsl?.let { location.mslAltitudeMeters = it.toDouble() }
            settings.effectiveMslAccuracy?.let { location.mslAltitudeAccuracyMeters = it }
        }
    }

    /** A fake fix reported as coming from [provider], with no original to copy metadata from. */
    private fun fakeLocation(provider: String): Location {
        val location = Location(provider)
        location.time = System.currentTimeMillis() - 300
        location.latitude = newlat
        location.longitude = newlng
        location.accuracy = accuracy
        applyOptionalFields(location)
        try {
            HiddenApiBypass.invoke(
                location.javaClass, location, "setIsFromMockProvider", false
            )
        } catch (e: Exception) {
            module.log(Log.WARN, TAG, "unable to clear the mock provider flag", e)
        }
        return location
    }

    /**
     * Whether a system-server call should be answered with a fake location.
     *
     * The caller's package name travels with the call, so the decision can be made per app without
     * the module being loaded into that app at all -- which is the whole point of driving this
     * from the system side.
     */
    private fun shouldSpoofCall(chain: XposedInterface.Chain): Boolean {
        if (!settings.isStarted) return false
        if (settings.spoofAllScoped) return true
        val callers = CallerIdentity.packagesFrom(chain.args)
        val hit = callers.firstOrNull { settings.shouldSpoof(it) }
        if (hit != null && settings.isVerboseLogging) {
            module.log(Log.DEBUG, TAG, "system: faking for $hit")
        }
        return hit != null
    }

    // ---------------------------------------------------------------- system server

    fun hookSystemServer(classLoader: ClassLoader) {
        if (!settings.isHookedSystem) {
            module.log(Log.INFO, TAG, "system server hooks disabled by settings")
            return
        }
        module.log(Log.INFO, TAG, "hooking system server")
        updateLocation("android")

        if (Build.VERSION.SDK_INT < 34) {
            hookLegacyLocationManagerService(classLoader)
        } else {
            hookModernLocationManagerService(classLoader)
        }
    }

    private fun hookLegacyLocationManagerService(classLoader: ClassLoader) {
        val serviceClass = Class.forName(
            "com.android.server.LocationManagerService", false, classLoader
        )

        module.hook(
            serviceClass.getDeclaredMethod(
                "getLastLocation", LocationRequest::class.java, String::class.java
            )
        ).intercept { chain ->
            if (!shouldSpoofCall(chain)) return@intercept chain.proceed()
            refresh("android", SYSTEM_INTERVAL)
            fakeLocation(LocationManager.GPS_PROVIDER)
        }

        for (method in serviceClass.declaredMethods) {
            if (method.returnType == Boolean::class.javaPrimitiveType &&
                method.name in GNSS_BOOLEAN_METHODS
            ) {
                protectedHook(method) { chain ->
                    if (shouldSpoofCall(chain)) false else chain.proceed()
                }
            }
        }

        module.hook(
            Class.forName(
                "com.android.server.LocationManagerService\$Receiver", false, classLoader
            ).getDeclaredMethod("callLocationChangedLocked", Location::class.java)
        ).intercept { chain -> replaceFirstLocationArg(chain, "android", SYSTEM_INTERVAL) }
    }

    private fun hookModernLocationManagerService(classLoader: ClassLoader) {
        val serviceClass = Class.forName(
            "com.android.server.location.LocationManagerService", false, classLoader
        )

        for (method in serviceClass.declaredMethods) {
            if (method.name == "getLastLocation" && method.returnType == Location::class.java) {
                protectedHook(method) { chain ->
                    if (!shouldSpoofCall(chain)) return@protectedHook chain.proceed()
                    refresh("android", SYSTEM_INTERVAL)
                    fakeLocation(LocationManager.GPS_PROVIDER)
                }
            } else if (method.returnType == Void.TYPE && method.name in GNSS_VOID_METHODS) {
                protectedHook(method) { chain ->
                    if (shouldSpoofCall(chain)) null else chain.proceed()
                }
            }
        }

        // getCurrentLocation is the modern one-shot request. Left unhooked it hands apps a real
        // fix straight from the provider, bypassing everything else here.
        for (method in serviceClass.declaredMethods) {
            if (method.name != "getCurrentLocation") continue
            protectedHook(method) { chain ->
                if (!shouldSpoofCall(chain)) return@protectedHook chain.proceed()
                if (settings.isVerboseLogging) {
                    module.log(Log.DEBUG, TAG, "system: blocked getCurrentLocation for target")
                }
                null
            }
        }

        runCatching {
            protectedHook(
                serviceClass.getDeclaredMethod("injectLocation", Location::class.java)
            ) { chain -> replaceFirstLocationArg(chain, "android", SYSTEM_INTERVAL) }
        }.onFailure { module.log(Log.WARN, TAG, "injectLocation not hooked: $it") }

        hookProviderDispatch(classLoader)
        hookGeofencing(classLoader)
        hookSystemGnss(serviceClass)
    }

    // ---------------------------------------------------------------- system GNSS status

    /** One synthetic satellite feed per registered app listener, so each can be stopped on its own. */
    private val systemGnssFeeds = java.util.concurrent.ConcurrentHashMap<Any, java.util.Timer>()

    /**
     * Feeds a synthetic satellite constellation to stealth-targeted apps from system_server.
     *
     * The app-side [GnssHooks] only reaches apps the module is loaded into (scoped). Apps targeted
     * by the stealth method are not scoped, so without this they would receive the REAL satellites
     * for the real location while the position is faked -- a contradiction a satellite-aware app
     * could detect. Here the registration is intercepted in system_server: the real satellite status
     * is blocked for a target, and (when synthesis is on) a plausible constellation is delivered to
     * the app's `IGnssStatusListener` binder instead, gated by the same caller identity as location.
     */
    private fun hookSystemGnss(serviceClass: Class<*>) {
        for (method in serviceClass.declaredMethods) {
            when (method.name) {
                "registerGnssStatusCallback" -> protectedHook(method) { chain ->
                    if (!shouldSpoofCall(chain)) return@protectedHook chain.proceed()
                    val listener = gnssListenerArg(method, chain)
                        ?: return@protectedHook chain.proceed() // can't identify it -- don't break GNSS
                    refresh("android", SYSTEM_INTERVAL)
                    if (settings.fakeGnss) startSystemGnssFeed(listener)
                    if (settings.isVerboseLogging) {
                        module.log(Log.DEBUG, TAG, "system gnss: intercepted status callback for target")
                    }
                    // Block the real satellite status either way, so real satellites never leak for
                    // a target; a synthetic feed replaces them when synthesis is enabled.
                    null
                }
                "unregisterGnssStatusCallback" -> protectedHook(method) { chain ->
                    gnssListenerArg(method, chain)?.let { stopSystemGnssFeed(it) }
                    chain.proceed()
                }
            }
        }
    }

    /** The IGnssStatusListener argument, located by the method's own parameter types. */
    private fun gnssListenerArg(method: java.lang.reflect.Method, chain: XposedInterface.Chain): Any? {
        val idx = method.parameterTypes.indexOfFirst { it.name.endsWith("IGnssStatusListener") }
        return if (idx >= 0) chain.args.getOrNull(idx) else null
    }

    private fun startSystemGnssFeed(listener: Any) {
        if (systemGnssFeeds.containsKey(listener)) return
        // The binder listener exposes onGnssStarted()/onFirstFix(int)/onSvStatusChanged(GnssStatus).
        val svMethod = listener.javaClass.methods.firstOrNull { m ->
            m.parameterTypes.size == 1 &&
                m.parameterTypes[0] == android.location.GnssStatus::class.java &&
                (m.name == "onSvStatusChanged" || m.name == "onGnssStatusChanged")
        } ?: run {
            module.log(Log.WARN, TAG, "system gnss: no onSvStatusChanged on ${listener.javaClass.name}")
            return
        }
        val startedM = runCatching { listener.javaClass.getMethod("onGnssStarted") }.getOrNull()
        val firstFixM = runCatching {
            listener.javaClass.getMethod("onFirstFix", Int::class.javaPrimitiveType)
        }.getOrNull()

        runCatching { startedM?.invoke(listener) }
        var first = true
        val timer = java.util.Timer("gnss-feed", true)
        timer.scheduleAtFixedRate(object : java.util.TimerTask() {
            override fun run() {
                runCatching {
                    val status = SyntheticGnss.build(newlat, newlng) ?: return
                    if (first) { runCatching { firstFixM?.invoke(listener, 1200) }; first = false }
                    svMethod.invoke(listener, status)
                }.onFailure {
                    // Delivery failed -- almost always because the app was killed and its listener
                    // binder is now dead. Stop this feed instead of calling a dead listener forever;
                    // if the app comes back it will register again and get a fresh feed.
                    module.log(Log.WARN, TAG, "system gnss feed stopped (listener gone): $it")
                    stopSystemGnssFeed(listener)
                }
            }
        }, 0L, 1000L)
        systemGnssFeeds[listener] = timer
        module.log(Log.INFO, TAG, "system gnss: feeding synthetic constellation to a target caller")
    }

    private fun stopSystemGnssFeed(listener: Any?) {
        listener ?: return
        systemGnssFeeds.remove(listener)?.cancel()
    }

    /**
     * Rewrites fixes on the path the platform actually uses to deliver updates to listening apps
     * on Android 12+.
     *
     * The LocationResult is modified **in place** and the call proceeds normally, so the platform
     * does its own dispatch. Deliberately no per-registration filtering: XposedFakeLocation
     * reflectively swaps LocationProviderManager.mRegistrations for a filtered copy, and its
     * issues #26 and #50 report system_server soft reboots from exactly that -- IllegalStateException
     * in LocationEventLog$AggregateStats and NullPointerException in Registration.onForegroundChanged,
     * on the android.fg thread, across four devices and both Android 14 and 16. Mutating a live
     * platform collection from a hook thread races ListenerMultiplexer.updateRegistrations.
     * Per-app control comes from the app-side hooks and the target list instead.
     */
    /**
     * Faking location updates for listening apps, decided per listener by which app owns it.
     *
     * The hook point is `LocationProviderManager$Registration.acceptLocationChange(LocationResult)`.
     * The platform calls it once per registered listener when a new fix arrives; the registration
     * object carries `getIdentity()` (the owning package), and the fix to deliver is the argument.
     * Rewriting that argument only for targeted registrations sends the fake fix to chosen apps
     * while everyone else gets the real one -- and it never touches the platform's registration
     * collection, so it avoids the concurrent-modification crash that hit XposedFakeLocation
     * (its issues #26 / #50).
     *
     * The classes are loaded by name rather than via `declaredClasses`, because ART strips the
     * InnerClasses metadata from the boot-image framework, so reflection over declared classes
     * comes back empty on a real device (which is why an earlier attempt logged "no Registration
     * class found").
     */
    private fun hookProviderDispatch(classLoader: ClassLoader) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        val locationResultClass = runCatching {
            Class.forName("android.location.LocationResult")
        }.getOrElse {
            module.log(Log.WARN, TAG, "LocationResult class missing: $it")
            return
        }

        // The concrete override lives on LocationRegistration; the base Registration declares it
        // too. Hook whichever ones resolve, de-duplicated by the actual Method.
        val classNames = listOf(
            "com.android.server.location.provider.LocationProviderManager\$LocationRegistration",
            "com.android.server.location.provider.LocationProviderManager\$Registration",
        )
        val hookedMethods = HashSet<String>()
        var hooked = 0
        for (name in classNames) {
            val cls = runCatching { Class.forName(name, false, classLoader) }.getOrNull() ?: continue
            val method = runCatching {
                cls.getDeclaredMethod("acceptLocationChange", locationResultClass)
            }.getOrNull() ?: continue
            // The base Registration declares acceptLocationChange as ABSTRACT; the framework refuses
            // to hook abstract methods (throws IllegalArgumentException), which previously aborted the
            // whole system-server install before geofencing. Only the concrete override is hookable.
            if (java.lang.reflect.Modifier.isAbstract(method.modifiers)) continue
            // getDeclaredMethod on a subclass can return the inherited base method; skip repeats.
            if (!hookedMethods.add(method.toString())) continue

            // Isolate each hook so one class failing cannot abort the rest of the system hooks.
            runCatching {
            protectedHook(method) { chain ->
                if (!settings.isStarted) return@protectedHook chain.proceed()

                val registration = chain.thisObject
                val pkg = registrationPackage(registration)
                val doSpoof = settings.spoofAllScoped ||
                    (pkg != null && settings.shouldSpoof(pkg))
                if (!doSpoof) return@protectedHook chain.proceed()

                refresh("android", SYSTEM_INTERVAL)
                val args = chain.args.toTypedArray()
                val faked = runCatching { fakeLocationResult(args.getOrNull(0)) }
                    .onFailure { module.log(Log.WARN, TAG, "acceptLocationChange rewrite failed: $it") }
                    .getOrNull()
                if (faked != null) {
                    // Substitute a fresh LocationResult for THIS registration only. Never mutate the
                    // shared original, or registrations dispatched after this one leak the fake.
                    args[0] = faked
                    if (settings.isVerboseLogging) {
                        module.log(Log.DEBUG, TAG, "system: delivered fake to ${pkg ?: "?"}")
                    }
                }
                chain.proceed(args)
            }
            hooked++
            }.onFailure { module.log(Log.WARN, TAG, "acceptLocationChange hook on $name failed: $it") }
        }
        module.log(
            Log.INFO, TAG,
            if (hooked > 0) "provider dispatch hooks: $hooked (acceptLocationChange)"
            else "provider dispatch: acceptLocationChange not found on this build"
        )
    }

    /** Reads the owning package from a Registration via its getIdentity().getPackageName(). */
    private fun registrationPackage(registration: Any?): String? {
        if (registration == null) return null
        return runCatching {
            val identity = registration.javaClass
                .getMethod("getIdentity")
                .apply { isAccessible = true }
                .invoke(registration) ?: return null
            identity.javaClass.getMethod("getPackageName").invoke(identity) as? String
        }.getOrElse {
            // Fall back to scanning fields if the accessor is absent on a vendor build.
            CallerIdentity.packagesFrom(listOf(registration)).firstOrNull()
        }
    }

    /**
     * Builds a NEW LocationResult carrying the fake fixes, or null if there is nothing to fake.
     *
     * The platform dispatches ONE LocationResult instance to every registration in a single
     * onReportLocation pass, so mutating it in place leaks the fake to every registration processed
     * afterwards -- non-targeted apps and the system's own blue dot. Returning a fresh object keeps
     * the spoof scoped to the one caller whose args we substitute it into.
     */
    private fun fakeLocationResult(result: Any?): Any? {
        if (result == null) return null
        val cls = result.javaClass
        val field = cls.getDeclaredField("mLocations").apply { isAccessible = true }
        val originals = (field.get(result) as? List<*>)?.filterIsInstance<Location>() ?: return null
        if (originals.isEmpty()) return null
        val fakes = originals.map { fakeLocation(it) }
        // Prefer the platform factory. Fall back to a Parcel deep-copy so we still avoid touching the
        // shared original on a vendor build where create(List) is absent.
        runCatching { cls.getMethod("create", List::class.java).invoke(null, fakes) }
            .getOrNull()?.let { return it }
        val copy = deepCopyParcelable(result) ?: return null
        cls.getDeclaredField("mLocations").apply { isAccessible = true }.set(copy, fakes)
        return copy
    }

    /** Independent copy of a Parcelable via a Parcel round-trip, so edits never reach the original. */
    private fun deepCopyParcelable(obj: Any): Any? = runCatching {
        val parcel = android.os.Parcel.obtain()
        try {
            (obj as android.os.Parcelable).writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            @Suppress("UNCHECKED_CAST")
            val creator = obj.javaClass.getField("CREATOR")
                .get(null) as android.os.Parcelable.Creator<Any>
            creator.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }.getOrNull()

    /** Geofences would otherwise fire against the device's real position. */
    private fun hookGeofencing(classLoader: ClassLoader) {
        if (!settings.blockGeofence) return
        val geofenceClass = runCatching {
            Class.forName(
                "com.android.server.location.geofence.GeofenceManager", false, classLoader
            )
        }.getOrNull() ?: return

        var hooked = 0
        for (method in geofenceClass.declaredMethods) {
            if (method.name != "addGeofence") continue
            protectedHook(method) { chain ->
                if (shouldSpoofCall(chain)) null else chain.proceed()
            }
            hooked++
        }
        module.log(Log.INFO, TAG, "geofence hooks: $hooked")
    }

    /**
     * Installs a hook that can never take system_server down.
     *
     * PROTECTIVE means the framework catches anything the interceptor throws and proceeds as if
     * the hook were absent. In an app that would be a bug; in system_server it is the difference
     * between a logged warning and the phone's UI restarting. Upstream issue #49 shows the
     * alternative: an exception escaping a system-service hook stopped LocationPolicyManagerService
     * from starting on every boot.
     */
    private fun protectedHook(
        method: java.lang.reflect.Executable,
        hooker: XposedInterface.Hooker,
    ) {
        module.hook(method)
            .setExceptionMode(hookMode(settings.isVerboseLogging))
            .intercept(hooker)
    }

    // ---------------------------------------------------------------- hooked apps

    fun hookApp(packageName: String, classLoader: ClassLoader) {
        var installed = 0

        // android.location.Location and LocationManager live on the boot classpath, so they are
        // the same classes in every process -- no classloader lookup needed.
        // Every getter an app might read directly. Hooking only lat/lng/accuracy leaves the
        // remaining fields describing the device's real state, which contradicts the fake fix.
        for (method in Location::class.java.declaredMethods) {
            if (method.parameterCount != 0) continue
            val value: (() -> Any?)? = when (method.name) {
                "getLatitude" -> ({ newlat })
                "getLongitude" -> ({ newlng })
                "getAccuracy" -> ({ accuracy })
                "getAltitude" -> ({ settings.effectiveAltitude })
                "getSpeed" -> ({ settings.effectiveSpeed })
                "getBearing" -> ({ settings.effectiveBearing })
                "getSpeedAccuracyMetersPerSecond" -> ({ settings.effectiveSpeedAccuracy })
                "getBearingAccuracyDegrees" -> ({ settings.effectiveBearingAccuracy })
                "getVerticalAccuracyMeters" -> ({ settings.effectiveVerticalAccuracy })
                "getMslAltitudeMeters" ->
                    if (Build.VERSION.SDK_INT >= 34) ({ settings.effectiveMsl?.toDouble() }) else null
                "getMslAltitudeAccuracyMeters" ->
                    if (Build.VERSION.SDK_INT >= 34) ({ settings.effectiveMslAccuracy }) else null
                else -> null
            }
            if (value != null) {
                hookAppGetter(packageName, method, value)
                installed++
            }
        }

        installed++
        Location::class.java.getDeclaredMethod("set", Location::class.java).let { m ->
            module.hook(m)
                .setExceptionMode(hookMode(settings.isVerboseLogging))
                .intercept { chain -> replaceFirstLocationArg(chain, packageName, APP_INTERVAL) }
            deopt(m)
        }

        installed++
        LocationManager::class.java.getDeclaredMethod("getLastKnownLocation", String::class.java).let { m ->
            module.hook(m)
                .setExceptionMode(hookMode(settings.isVerboseLogging))
                .intercept { chain ->
                    refresh(packageName, APP_INTERVAL)
                    if (!settings.shouldSpoof(packageName)) return@intercept chain.proceed()
                    fakeLocation(chain.getArg(0) as String)
                }
            deopt(m)
        }

        installed += hookFusedLocation(packageName, classLoader)

        module.log(Log.INFO, TAG, "installed $installed hooks in $packageName")
    }

    /**
     * Faking Google Play Services' fused-location results inside the app.
     *
     * The base android.location.Location getter hooks already cover most reads, but Google apps
     * often pull the fix out of a `com.google.android.gms.location.LocationResult`, and some code
     * paths read its fields without going through getLatitude(). Faking LocationResult directly,
     * and reporting location as always available, closes those gaps so a targeted app shows the
     * fake position from every source -- GPS, Wi-Fi/cell, or fused -- including indoors where there
     * is no GPS at all. Guarded throughout: an app without Play Services simply skips this.
     */
    private fun hookFusedLocation(packageName: String, classLoader: ClassLoader): Int {
        var count = 0

        runCatching {
            val resultClass = Class.forName(
                "com.google.android.gms.location.LocationResult", false, classLoader
            )
            for (method in resultClass.declaredMethods) {
                when {
                    method.name == "getLastLocation" && method.returnType == Location::class.java -> {
                        module.hook(method)
                            .setExceptionMode(hookMode(settings.isVerboseLogging))
                            .intercept { chain ->
                                refresh(packageName, APP_INTERVAL)
                                if (!settings.shouldSpoof(packageName)) return@intercept chain.proceed()
                                fakeLocation(chain.proceed() as? Location)
                            }
                        deopt(method)
                        count++
                    }
                    method.name == "getLocations" -> {
                        module.hook(method)
                            .setExceptionMode(hookMode(settings.isVerboseLogging))
                            .intercept { chain ->
                                refresh(packageName, APP_INTERVAL)
                                if (!settings.shouldSpoof(packageName)) return@intercept chain.proceed()
                                val originals = (chain.proceed() as? List<*>)?.filterIsInstance<Location>()
                                    ?: return@intercept chain.proceed()
                                originals.map { fakeLocation(it) }
                            }
                        deopt(method)
                        count++
                    }
                }
            }
        }.onFailure { module.log(Log.DEBUG, TAG, "no GMS LocationResult in $packageName") }

        // Tell the app location is available, so it does not fall back to "no fix" indoors.
        runCatching {
            val availabilityClass = Class.forName(
                "com.google.android.gms.location.LocationAvailability", false, classLoader
            )
            availabilityClass.getDeclaredMethod("isLocationAvailable").let { method ->
                module.hook(method)
                    .setExceptionMode(hookMode(settings.isVerboseLogging))
                    .intercept { chain ->
                        if (settings.shouldSpoof(packageName)) true else chain.proceed()
                    }
                deopt(method)
                count++
            }
        }

        if (count > 0) module.log(Log.INFO, TAG, "installed $count GMS fused hooks in $packageName")
        return count
    }

    private fun hookAppGetter(packageName: String, method: Method, value: () -> Any?) {
        module.hook(method)
            .setExceptionMode(hookMode(settings.isVerboseLogging))
            .intercept { chain ->
                refresh(packageName, APP_INTERVAL)
                if (!settings.shouldSpoof(packageName)) return@intercept chain.proceed()
                // A null provider means "no opinion" -- leave the original value untouched rather
                // than substituting a zero.
                value() ?: chain.proceed()
            }
        // Location getters are tiny and prime candidates for ART to inline at call sites, which
        // would bypass the hook entirely. Deoptimizing forces real calls so the spoof always fires.
        deopt(method)
    }

    /**
     * Best-effort deoptimize: makes the runtime stop inlining callers of [method] so a hook on it
     * cannot be silently skipped. Uses the modern API's `deoptimize`; failures are harmless.
     */
    private fun deopt(method: java.lang.reflect.Executable) {
        runCatching {
            val ok = module.deoptimize(method)
            if (settings.isVerboseLogging) {
                module.log(Log.DEBUG, TAG, "deoptimize ${method.name}: $ok")
            }
        }
    }

    // ---------------------------------------------------------------- shared

    /**
     * Rewrites the single [Location] argument of the intercepted call and lets the rest of the
     * chain (and eventually the original method) run with the fake fix.
     */
    private fun replaceFirstLocationArg(
        chain: XposedInterface.Chain,
        packageName: String,
        interval: Long,
    ): Any? {
        refresh(packageName, interval)
        val gate = if (packageName == "android") settings.isStarted
                   else settings.shouldSpoof(packageName)
        if (!gate) return chain.proceed()
        val args = chain.args.toTypedArray()
        args[0] = fakeLocation(args[0] as Location?)
        return chain.proceed(args)
    }
}

private val GNSS_BOOLEAN_METHODS = setOf(
    "addGnssBatchingCallback",
    "addGnssMeasurementsListener",
    "addGnssNavigationMessageListener",
)

private val GNSS_VOID_METHODS = setOf(
    "startGnssBatch",
    "addGnssAntennaInfoListener",
    "addGnssMeasurementsListener",
    "addGnssNavigationMessageListener",
)

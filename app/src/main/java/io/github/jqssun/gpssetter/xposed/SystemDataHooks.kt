package io.github.jqssun.gpssetter.xposed

import android.os.Binder
import android.util.Log
import io.github.libxposed.api.XposedInterface

/**
 * Fakes the Wi-Fi and cell-tower data that location code reads, from the system side.
 *
 * This is what lets the stealth (system-side) method cover apps that work out their own position
 * from nearby Wi-Fi networks or cell towers -- including indoors, where there is no GPS -- without
 * the module being loaded into those apps.
 *
 * Everything here is decided per caller: we only blank the data when the app asking is a spoof
 * target, so the phone's own Wi-Fi list and dialer keep working normally. And every hook runs in
 * PROTECTIVE mode with its body wrapped, because a throw in system_server or the phone process is a
 * reboot, not a caught error -- this is exactly the class of bug that boot-loops the reference app
 * (its issue #49), and it is avoided here by never letting anything escape.
 */
class SystemDataHooks(
    private val module: XposedInterface,
    private val settings: Xshare,
) {
    private companion object {
        const val TAG = "GPS Setter"
    }

    /**
     * True when the app behind the current binder call is a spoof target.
     *
     * Resolving a uid to its packages needs the package manager, reached here through the hidden
     * AppGlobals.getPackageManager() by reflection (this runs in system_server / the phone process,
     * where that is available). Any failure means "not a target", so real data is returned.
     */
    private fun callerIsTarget(): Boolean {
        if (!settings.isStarted) return false
        return runCatching {
            val uid = Binder.getCallingUid()
            val appGlobals = Class.forName("android.app.AppGlobals")
            val pm = appGlobals.getMethod("getPackageManager").invoke(null) ?: return false
            val packages = (pm.javaClass.getMethod("getPackagesForUid", Int::class.javaPrimitiveType)
                .invoke(pm, uid) as? Array<*>)?.filterIsInstance<String>() ?: return false
            packages.any { settings.shouldSpoof(it) }
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------ Wi-Fi (system_server)

    /**
     * Hooks the Wi-Fi service so a targeted app sees no real nearby networks -- it cannot then work
     * out a real position from them.
     *
     * The Wi-Fi service lives in a separate mainline module with its own classloader, so we catch
     * it as system_server loads it (via [android.os.SystemService]-style class loading) rather than
     * by name. The catch point is a hot, general method, so the body is paranoid: it only ever acts
     * on the exact Wi-Fi service class and never lets anything through that could disturb the load
     * of any other service.
     */
    fun installWifiHooks(systemClassLoader: ClassLoader) {
        val ssm = runCatching {
            Class.forName("com.android.server.SystemServiceManager", false, systemClassLoader)
        }.getOrNull() ?: run {
            module.log(Log.INFO, TAG, "SystemServiceManager not found; wifi hooks skipped")
            return
        }
        val method = runCatching {
            ssm.getDeclaredMethod("loadClassFromLoader", String::class.java, ClassLoader::class.java)
        }.getOrNull() ?: run {
            module.log(Log.INFO, TAG, "loadClassFromLoader not found; wifi hooks skipped")
            return
        }

        val installed = java.util.concurrent.atomic.AtomicBoolean(false)
        module.hook(method)
            .setExceptionMode(hookMode(settings.isVerboseLogging))
            .intercept { chain ->
                val result = chain.proceed()
                // Everything below is best-effort; it must NEVER affect the class load itself.
                runCatching {
                    val name = chain.args.getOrNull(0) as? String
                    if (name == "com.android.server.wifi.WifiService" && !installed.get()) {
                        val loader = chain.args.getOrNull(1) as? ClassLoader
                        if (loader != null && hookWifiServiceImpl(loader)) {
                            installed.set(true)
                        }
                    }
                }
                result
            }
        module.log(Log.INFO, TAG, "wifi service watcher installed")
    }

    private fun hookWifiServiceImpl(loader: ClassLoader): Boolean {
        val cls = runCatching {
            Class.forName("com.android.server.wifi.WifiServiceImpl", false, loader)
        }.getOrNull() ?: return false

        var hooked = 0
        for (method in cls.declaredMethods) {
            when (method.name) {
                "getScanResults" -> {
                    module.hook(method)
                        .setExceptionMode(hookMode(settings.isVerboseLogging))
                        .intercept { chain ->
                            if (callerIsTarget()) {
                                if (settings.isVerboseLogging) {
                                    module.log(Log.DEBUG, TAG, "wifi: cleared scan results for target")
                                }
                                emptyList<Any>()
                            } else {
                                chain.proceed()
                            }
                        }
                    hooked++
                }
            }
        }
        module.log(Log.INFO, TAG, "wifi hooks installed: $hooked")
        return hooked > 0
    }

    // ------------------------------------------------------------ cell (com.android.phone)

    /**
     * Hooks the phone process so a targeted app sees no real cell towers. Runs in com.android.phone
     * (which the user scopes for this), a separate process from the target app, so it stays
     * invisible to that app. A crash here would disrupt telephony, so the same paranoia applies.
     */
    fun installCellHooks(phoneClassLoader: ClassLoader) {
        val cls = runCatching {
            Class.forName("com.android.phone.PhoneInterfaceManager", false, phoneClassLoader)
        }.getOrNull() ?: run {
            module.log(Log.INFO, TAG, "PhoneInterfaceManager not found; cell hooks skipped")
            return
        }

        var hooked = 0
        for (method in cls.declaredMethods) {
            if (method.name == "getAllCellInfo" || method.name == "getNeighboringCellInfo") {
                module.hook(method)
                    .setExceptionMode(hookMode(settings.isVerboseLogging))
                    .intercept { chain ->
                        if (callerIsTarget()) {
                            if (settings.isVerboseLogging) {
                                module.log(Log.DEBUG, TAG, "cell: cleared ${method.name} for target")
                            }
                            emptyList<Any>()
                        } else {
                            chain.proceed()
                        }
                    }
                hooked++
            }
        }
        module.log(Log.INFO, TAG, "cell hooks installed: $hooked in com.android.phone")
    }
}

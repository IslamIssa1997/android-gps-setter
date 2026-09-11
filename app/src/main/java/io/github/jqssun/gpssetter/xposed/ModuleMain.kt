package io.github.jqssun.gpssetter.xposed

import android.app.Application
import android.util.Log
import android.widget.Toast
import io.github.libxposed.api.XposedInterface
import io.github.jqssun.gpssetter.BuildConfig
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Module entry point, declared in `META-INF/xposed/java_init.list`.
 *
 * Replaces the legacy `HookEntry` / `IXposedHookLoadPackage`: the framework now dispatches
 * separate callbacks for system server and for regular packages instead of a single
 * `handleLoadPackage`, and instantiates this class through its public no-arg constructor.
 */
class ModuleMain : XposedModule() {

    private companion object {
        const val TAG = "GPS Setter"

        /** Never spoof for these: our own app, and the fused provider shim. */
        val IGNORED_PACKAGES = setOf(
            "com.android.location.fused",
            BuildConfig.APPLICATION_ID,
        )
    }

    private val settings by lazy {
        Xshare(
            try {
                getRemotePreferences(XposedPrefs.GROUP)
            } catch (e: UnsupportedOperationException) {
                log(Log.ERROR, TAG, "framework has no remote preference support", e)
                null
            }
        )
    }

    private val hook by lazy { LocationHook(this, settings) }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        // Logged once per process so a captured logcat is self-describing: which build, which
        // framework, which API level, and what the module actually read from settings.
        log(
            Log.INFO, TAG,
            "v${BuildConfig.TAG_NAME} loaded into ${param.processName}" +
                (if (param.isSystemServer) " [system_server]" else "") +
                " | framework=$frameworkName $frameworkVersion (code $frameworkVersionCode)" +
                " | api=$apiVersion"
        )
        log(
            Log.INFO, TAG,
            "capabilities: system=${hasProp(PROP_CAP_SYSTEM)}" +
                " remote=${hasProp(PROP_CAP_REMOTE)}" +
                " apiProtection=${hasProp(PROP_RT_API_PROTECTION)}"
        )
        log(Log.INFO, TAG, "settings: ${settings.describe()}")
    }

    private fun hasProp(prop: Long) = (frameworkProperties and prop) != 0L

    private val systemData by lazy { SystemDataHooks(this, settings) }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        runCatching { hook.hookSystemServer(param.classLoader) }
            .onFailure { log(Log.ERROR, TAG, "failed to hook system server", it) }
        // Wi-Fi data faking lives in system_server, gated per caller, so only when system hooks
        // are enabled at all.
        if (settings.isHookedSystem) {
            runCatching { systemData.installWifiHooks(param.classLoader) }
                .onFailure { log(Log.ERROR, TAG, "failed to install wifi hooks", it) }
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage) return
        if (param.packageName in IGNORED_PACKAGES) return

        val pkg = param.packageName

        // The phone process is not a spoof target itself -- it is scoped so we can fake the cell
        // towers it serves to targeted apps. Handle it separately and stop.
        if (pkg == "com.android.phone") {
            runCatching { systemData.installCellHooks(param.classLoader) }
                .onFailure { log(Log.ERROR, TAG, "cell hooks failed", it) }
            return
        }

        // Each hook set is installed independently: one failing must not prevent the others,
        // and none of them may take the host app down with it.
        runCatching { hook.hookApp(pkg, param.classLoader) }
            .onFailure { log(Log.ERROR, TAG, "location hooks failed for $pkg", it) }

        runCatching { GnssHooks(this, settings, pkg).install() }
            .onFailure { log(Log.ERROR, TAG, "gnss hooks failed for $pkg", it) }

        runCatching { WifiHooks(this, settings, pkg).install() }
            .onFailure { log(Log.ERROR, TAG, "wifi hooks failed for $pkg", it) }

        runCatching { CellHooks(this, settings, pkg).install() }
            .onFailure { log(Log.ERROR, TAG, "cell hooks failed for $pkg", it) }

        if (!settings.hideActiveToast && settings.shouldSpoof(pkg)) {
            runCatching { showActiveToast(param) }
                .onFailure { log(Log.WARN, TAG, "could not show toast in $pkg", it) }
        }
    }

    /**
     * Shows a brief confirmation inside the hooked app once its Application context exists, so it
     * is obvious at a glance whether the module actually took effect there.
     */
    private fun showActiveToast(param: PackageReadyParam) {
        val instrumentation = Class.forName(
            "android.app.Instrumentation", false, param.classLoader
        )
        val method = instrumentation.getDeclaredMethod(
            "callApplicationOnCreate", Application::class.java
        )
        hook(method)
            .setExceptionMode(hookMode(settings.isVerboseLogging))
            .intercept { chain ->
                val result = chain.proceed()
                runCatching {
                    val context = (chain.getArg(0) as Application).applicationContext
                    Toast.makeText(context, "GPS Setter active", Toast.LENGTH_SHORT).show()
                }
                result
            }
    }
}

package io.github.jqssun.gpssetter.xposed

import android.telephony.TelephonyManager
import android.util.Log
import io.github.libxposed.api.XposedInterface

/**
 * Stops an app cross-checking your position against the phone masts around you.
 *
 * A phone reports which masts it can see, and those have known real-world positions. An app that
 * sees masts in one city while the location claims another has caught the spoof without needing
 * anything clever.
 *
 * This runs inside the target app rather than in the phone process. The answer to
 * `getAllCellInfo()` and friends arrives in the calling app's own process, so it can be rewritten
 * there -- which means no extra entry in the module's scope, and one less process for an app to
 * notice the framework in.
 */
class CellHooks(
    private val module: XposedInterface,
    private val settings: Xshare,
    private val packageName: String,
) {
    private companion object {
        const val TAG = "GPS Setter"

        /** Methods that hand an app the masts it can currently see. */
        val CELL_INFO_METHODS = setOf(
            "getAllCellInfo",
            "getNeighboringCellInfo",
            "requestCellInfoUpdate",
        )
    }

    fun install() {
        var hooked = 0
        for (method in TelephonyManager::class.java.declaredMethods) {
            if (method.name !in CELL_INFO_METHODS) continue

            module.hook(method)
                .setExceptionMode(hookMode(settings.isVerboseLogging))
                .intercept { chain ->
                    if (!settings.shouldSpoof(packageName)) return@intercept chain.proceed()

                    // Returning the real masts would give the true location away. Inventing masts
                    // is worse: their identifiers are looked up in public databases, and ones that
                    // do not exist -- or exist in the wrong country -- are a louder signal than
                    // having none. So report nothing, which is what a phone with no signal shows.
                    val replacement: Any? = when {
                        method.returnType == Boolean::class.javaPrimitiveType -> false
                        List::class.java.isAssignableFrom(method.returnType) -> emptyList<Any>()
                        else -> null
                    }
                    if (settings.isVerboseLogging) {
                        module.log(
                            Log.DEBUG, TAG,
                            "cell: blocked ${method.name} in $packageName"
                        )
                    }
                    replacement
                }
            hooked++
        }
        module.log(
            Log.INFO, TAG,
            if (hooked > 0) "cell: $hooked mast lookups blocked in $packageName"
            else "cell: no mast lookups found to hook in $packageName"
        )
    }
}

package io.github.jqssun.gpssetter.xposed

import android.net.wifi.WifiManager
import android.util.Log
import io.github.libxposed.api.XposedInterface
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Makes the connected access point look like something other than the user's real network.
 *
 * Wi-Fi is one of the cheapest cross-checks against a GPS fix: a device claiming to be in another
 * country while reporting the SSID and BSSID of a known home network is trivially detectable.
 *
 * Only the app-side `WifiManager` calls are hooked. The system services are deliberately left
 * alone -- upstream issue #49 in XposedFakeLocation shows what happens when a module reaches them
 * through `SystemServiceManager.loadClassFromLoader`: that method loads every system service, and
 * a failure inside the interceptor stops `LocationPolicyManagerService` from starting on boot.
 */
class WifiHooks(
    private val module: XposedInterface,
    private val settings: Xshare,
    private val packageName: String,
) {
    private companion object {
        const val TAG = "GPS Setter"
    }

    fun install() {
        if (!settings.wifiIdentityEnabled) return

        hookConnectionInfo()
        hookScanResults()
    }

    private fun hookConnectionInfo() {
        val method = runCatching {
            WifiManager::class.java.getDeclaredMethod("getConnectionInfo")
        }.getOrElse {
            module.log(Log.WARN, TAG, "WifiManager.getConnectionInfo not found: $it")
            return
        }

        module.hook(method)
            .setExceptionMode(hookMode(settings.isVerboseLogging))
            .intercept { chain ->
                val info = chain.proceed() ?: return@intercept null
                if (!settings.shouldSpoof(packageName)) return@intercept info
                runCatching {
                    // These setters are hidden API, hence the bypass.
                    HiddenApiBypass.invoke(info.javaClass, info, "setSSID", wrapSsid(settings.wifiSsid))
                    HiddenApiBypass.invoke(info.javaClass, info, "setBSSID", settings.wifiBssid)
                    HiddenApiBypass.invoke(info.javaClass, info, "setRssi", settings.wifiRssi)
                }.onFailure {
                    module.log(Log.WARN, TAG, "could not rewrite WifiInfo: $it")
                }
                info
            }
        module.log(Log.INFO, TAG, "wifi identity hook installed for $packageName")
    }

    private fun hookScanResults() {
        val method = runCatching {
            WifiManager::class.java.getDeclaredMethod("getScanResults")
        }.getOrElse { return }

        module.hook(method)
            .setExceptionMode(hookMode(settings.isVerboseLogging))
            .intercept { chain ->
                if (!settings.shouldSpoof(packageName)) return@intercept chain.proceed()

                // Returning the real neighbours would give the true location away as readily as
                // the connected network. Returning an empty list is also wrong: a phone in a city
                // that can see no networks at all is itself unusual. So report a small synthetic
                // neighbourhood, including the access point we claim to be connected to.
                val results = chain.proceed()
                runCatching { buildSyntheticScan(results) }
                    .getOrElse {
                        module.log(Log.WARN, TAG, "synthetic scan unavailable: $it")
                        results
                    }
            }
    }

    /**
     * Rewrites the entries of a real scan result list in place, keeping the platform's own objects
     * so nothing downstream sees an unexpected type. The first entry becomes the fake connected
     * access point; the rest get derived addresses and weaker signals, as neighbours would.
     */
    private fun buildSyntheticScan(original: Any?): Any? {
        val list = original as? List<*> ?: return original
        if (list.isEmpty()) return list

        val baseMac = settings.wifiBssid
        list.forEachIndexed { index, entry ->
            if (entry == null) return@forEachIndexed
            val cls = entry.javaClass
            runCatching {
                cls.getField("SSID").set(entry, if (index == 0) settings.wifiSsid else "")
                cls.getField("BSSID").set(entry, derivedMac(baseMac, index))
                cls.getField("level").set(
                    entry,
                    // The connected AP is strongest; neighbours fall away from it.
                    (settings.wifiRssi - index * 7).coerceAtLeast(-95)
                )
            }
        }
        return list
    }

    /** Derives neighbour MACs from the configured one so the set looks like one location. */
    private fun derivedMac(base: String, index: Int): String {
        if (index == 0) return base
        val parts = base.split(":").toMutableList()
        if (parts.size != 6) return base
        val last = parts[5].toIntOrNull(16) ?: return base
        parts[5] = "%02x".format((last + index) and 0xff)
        return parts.joinToString(":")
    }

    /**
     * `WifiInfo.setSSID` takes a hidden `WifiSsid`; fall back to the raw string when that type is
     * unavailable, which is what older platform versions expect.
     */
    private fun wrapSsid(ssid: String): Any = runCatching {
        val cls = Class.forName("android.net.wifi.WifiSsid")
        HiddenApiBypass.invoke(cls, null, "fromBytes", ssid.toByteArray())
    }.getOrElse { ssid }
}

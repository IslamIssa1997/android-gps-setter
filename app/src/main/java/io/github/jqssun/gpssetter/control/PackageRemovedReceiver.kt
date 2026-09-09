package io.github.jqssun.gpssetter.control

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.jqssun.gpssetter.utils.PrefManager
import timber.log.Timber

/**
 * Removes an app from the target list the moment it is uninstalled, so the list stays clean without
 * waiting for the next app launch or for the Target Apps screen to be reopened.
 *
 * `PACKAGE_FULLY_REMOVED` is a protected, system-only broadcast that fires only on a real uninstall
 * (not on an app update) -- so this wakes the app briefly on that rare event, never continuously.
 */
class PackageRemovedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_FULLY_REMOVED) return
        val pkg = intent.data?.schemeSpecificPart ?: return
        PrefManager.removeTargetApp(pkg)
        Timber.i("pruned uninstalled app from target list: %s", pkg)
    }
}

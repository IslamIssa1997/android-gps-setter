package io.github.jqssun.gpssetter

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.MutableLiveData
import dagger.hilt.android.HiltAndroidApp
import io.github.jqssun.gpssetter.utils.PrefManager
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

lateinit var gsApp: App

@HiltAndroidApp
class App : Application(), XposedServiceHelper.OnServiceListener {
    val globalScope = CoroutineScope(Dispatchers.Default)

    companion object {
        /**
         * The bound Xposed service, or null when the module is not active. Replaces the old
         * "hook our own MainViewModel and see whether the hook fires" module-detection trick:
         * the framework binds this service only to modules it has actually loaded.
         *
         * Stays unset until the framework answers or [BIND_TIMEOUT_MS] elapses, so the UI does not
         * flash a "module missing" dialog during the async bind.
         */
        val xposedService = MutableLiveData<XposedService?>()

        @Volatile
        private var everBound = false

        private const val BIND_TIMEOUT_MS = 3000L

        fun commonInit() {
            if (BuildConfig.DEBUG) {
                Timber.plant(Timber.DebugTree())
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        gsApp = this
        commonInit()
        // A fresh process means no route is running. Clearing it here recovers from a navigation
        // service that was hard-killed (so its onDestroy never reset the flag), which would
        // otherwise leave the hook reporting a frozen route speed forever.
        PrefManager.routeActive = false
        PrefManager.migrateOnce()
        // Drop any targeted apps that have since been uninstalled, without needing the Target Apps
        // screen to be reopened. The package-removed receiver handles removals while running.
        PrefManager.pruneTargetApps()
        PrefManager.refreshElevationOnStart()
        AppCompatDelegate.setDefaultNightMode(PrefManager.darkTheme)
        // Restore the chosen language; an empty tag means follow the system.
        PrefManager.languageTag.takeIf { it.isNotEmpty() }?.let {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(it))
        }

        XposedServiceHelper.registerListener(this)
        globalScope.launch {
            delay(BIND_TIMEOUT_MS)
            if (!everBound) {
                xposedService.postValue(null)
            }
        }
    }

    override fun onServiceBind(service: XposedService) {
        Timber.i("Xposed service bound: %s %s", service.frameworkName, service.frameworkVersion)
        everBound = true
        PrefManager.attachService(service)
        xposedService.postValue(service)
    }

    override fun onServiceDied(service: XposedService) {
        Timber.w("Xposed service died")
        PrefManager.attachService(null)
        xposedService.postValue(null)
    }
}

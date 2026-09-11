package io.github.jqssun.gpssetter.update

import android.content.Context
import android.os.Parcelable
import io.github.jqssun.gpssetter.BuildConfig
import io.github.jqssun.gpssetter.utils.PrefManager
import kotlinx.parcelize.Parcelize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject


class UpdateChecker @Inject constructor(private val apiResponse : GitHubService) {

    /** Outcome of a check, so a network/parse FAILURE is never mistaken for "up to date". */
    sealed interface CheckResult {
        data class Available(val update: Update) : CheckResult
        data object UpToDate : CheckResult
        data object Failed : CheckResult
    }

    /**
     * @param force a manual "Check now": bypasses the auto-check-disabled toggle AND the
     *   ignored-version filter, so an explicit check always surfaces an available update.
     */
    fun getLatestRelease(force: Boolean = false) = callbackFlow {
        withContext(Dispatchers.IO){
            val release = getReleaseList()
            if (release == null) {
                // Couldn't reach GitHub or parse the response -- report FAILED, not UpToDate, or a
                // dropped request looks identical to "you're on the latest version".
                trySend(CheckResult.Failed).isSuccess
                return@withContext
            }

            val currentTag = release.tagName
            val isNewer = currentTag != null && currentTag != "v" + BuildConfig.TAG_NAME
            // Auto-checks respect the disable toggle and the per-version ignore; a manual check
            // ignores both. The ignore is per-version: a newer tag is never suppressed.
            val suppressed = !force &&
                (PrefManager.isUpdateDisabled || currentTag == PrefManager.ignoredUpdateVersion)

            if (isNewer && !suppressed) {
                val asset = release.assets?.firstOrNull { it.name?.endsWith(".apk") == true }
                val name = release.name
                val body = release.body
                val publishedAt = release.publishedAt
                if (name == null || body == null || publishedAt == null) {
                    // Malformed release payload -- don't claim an update we can't describe.
                    trySend(CheckResult.UpToDate).isSuccess
                    return@withContext
                }
                trySend(
                    CheckResult.Available(
                        Update(
                            name,
                            body,
                            publishedAt,
                            asset?.browserDownloadUrl
                                ?: "https://github.com/IslamIssa1997/android-gps-setter/releases",
                            asset?.name ?: "app-full-arm64-v8a-release.apk",
                            currentTag ?: ""
                        )
                    )
                ).isSuccess
            } else {
                trySend(CheckResult.UpToDate).isSuccess
            }
        }
        awaitClose {  }
    }


    private fun getReleaseList(): GitHubRelease? =
        runCatching { apiResponse.getReleases().execute().body() }
            .onFailure { Timber.w(it, "update check: failed to fetch latest release") }
            .getOrNull()

    fun clearCachedDownloads(context: Context){
        File(context.externalCacheDir, "updates").deleteRecursively()
    }

    @Parcelize
    data class Update(val name: String, val changelog: String, val timestamp: String, val assetUrl: String, val assetName: String, val tag: String):
        Parcelable
}


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
import java.io.File
import javax.inject.Inject


class UpdateChecker @Inject constructor(private val apiResponse : GitHubService) {


    /**
     * @param force a manual "Check now": bypasses the auto-check-disabled toggle AND the
     *   ignored-version filter, so an explicit check always surfaces an available update.
     */
    fun getLatestRelease(force: Boolean = false) = callbackFlow {
        withContext(Dispatchers.IO){
            getReleaseList()?.let { gitHubReleaseResponse ->
                val currentTag = gitHubReleaseResponse.tagName

                val isNewer = currentTag != null && currentTag != "v" + BuildConfig.TAG_NAME
                // Auto-checks respect the disable toggle and the per-version ignore; a manual check
                // ignores both. The ignore is per-version: a newer tag is never suppressed.
                val suppressed = !force &&
                    (PrefManager.isUpdateDisabled || currentTag == PrefManager.ignoredUpdateVersion)

                if (isNewer && !suppressed) {
                    val asset =
                        gitHubReleaseResponse.assets?.firstOrNull { it.name?.endsWith(".apk") == true }
                    val name = gitHubReleaseResponse.name ?: run {
                        this@callbackFlow.trySend(null).isSuccess
                        return@let
                    }
                    val body = gitHubReleaseResponse.body ?: run {
                        this@callbackFlow.trySend(null).isSuccess
                        return@let
                    }
                    val publishedAt = gitHubReleaseResponse.publishedAt ?: run {
                        this@callbackFlow.trySend(null).isSuccess
                        return@let
                    }
                    this@callbackFlow.trySend(
                        Update(
                            name,
                            body,
                            publishedAt,
                            asset?.browserDownloadUrl
                                ?: "https://github.com/IslamIssa1997/android-gps-setter/releases",
                            asset?.name ?: "app-full-arm64-v8a-release.apk",
                            currentTag ?: ""
                        )
                    ).isSuccess
                } else {
                    this@callbackFlow.trySend(null).isSuccess
                }
            } ?: run {
                this@callbackFlow.trySend(null).isSuccess
            }
        }
        awaitClose {  }
    }


    private fun getReleaseList(): GitHubRelease? {

        runCatching {
            apiResponse.getReleases().execute().body()
        }.onSuccess {
            return it
        }.onFailure {
            return null
        }
        return null
    }

    fun clearCachedDownloads(context: Context){
        File(context.externalCacheDir, "updates").deleteRecursively()
    }

    @Parcelize
    data class Update(val name: String, val changelog: String, val timestamp: String, val assetUrl: String, val assetName: String, val tag: String):
        Parcelable
}


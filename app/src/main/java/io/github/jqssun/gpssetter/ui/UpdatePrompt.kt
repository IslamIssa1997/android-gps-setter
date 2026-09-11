package io.github.jqssun.gpssetter.ui

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.ui.viewmodel.MainViewModel
import io.github.jqssun.gpssetter.update.UpdateChecker
import io.github.jqssun.gpssetter.utils.PrefManager
import kotlinx.coroutines.launch

/**
 * The update-available prompt, shared by the map (automatic check on start) and the Settings
 * "Check now" action so both offer the same three choices:
 *
 * - **Update now** – download the APK in place and launch the installer.
 * - **Remind me later** – just dismiss; the next automatic start-up check surfaces it again.
 * - **Ignore this update** – remember this release tag and stop prompting for it. It is per-version:
 *   a newer release supersedes the ignored tag and prompts again.
 */
fun AppCompatActivity.showUpdatePrompt(viewModel: MainViewModel, update: UpdateChecker.Update) {
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.update_available)
        .setMessage(update.changelog)
        .setPositiveButton(R.string.update_now) { _, _ -> startUpdateDownload(viewModel, update) }
        .setNeutralButton(R.string.update_remind_later) { d, _ ->
            viewModel.clearUpdate()
            d.dismiss()
        }
        .setNegativeButton(R.string.update_ignore) { d, _ ->
            PrefManager.ignoredUpdateVersion = update.tag
            viewModel.clearUpdate()
            d.dismiss()
        }
        .show()
}

/** Downloads the update APK with a progress dialog, then hands the file to the package installer. */
private fun AppCompatActivity.startUpdateDownload(viewModel: MainViewModel, update: UpdateChecker.Update) {
    val view = layoutInflater.inflate(R.layout.update_dialog, null)
    val progress = view.findViewById<LinearProgressIndicator>(R.id.update_download_progress)
    val cancel = view.findViewById<AppCompatButton>(R.id.update_download_cancel)
    val dialog = MaterialAlertDialogBuilder(this).setView(view).create()

    cancel.setOnClickListener {
        viewModel.cancelDownload(this)
        dialog.dismiss()
    }
    lifecycleScope.launch {
        viewModel.downloadState.collect { state ->
            when (state) {
                is MainViewModel.State.Downloading -> if (state.progress > 0) {
                    progress.isIndeterminate = false
                    progress.progress = state.progress
                }
                is MainViewModel.State.Done -> {
                    viewModel.openPackageInstaller(this@startUpdateDownload, state.fileUri)
                    viewModel.clearUpdate()
                    dialog.dismiss()
                }
                is MainViewModel.State.Failed -> {
                    Toast.makeText(this@startUpdateDownload, R.string.bs_update_download_failed, Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                }
                else -> {}
            }
        }
    }
    dialog.show()
    viewModel.startDownload(this, update)
}

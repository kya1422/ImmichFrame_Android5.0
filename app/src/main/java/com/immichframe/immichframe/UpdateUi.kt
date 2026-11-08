package com.immichframe.immichframe

import android.app.Activity
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/**
 * The small amount of UI around [UpdateChecker]: "check now", the release-notes dialog, the
 * one-time install permission and the final install prompt.
 *
 * Kept in one place because both the settings screen and the frame activity (via the update
 * notification) can start an update; the wording and the order of checks must be identical.
 */
object UpdateUi {

    /** Checks GitHub and either reports "up to date" or offers the newest release. */
    fun checkAndPrompt(activity: Activity) {
        val ctx = activity
        if (!UpdateChecker.isOnline(ctx)) {
            toast(ctx, ctx.getString(R.string.update_offline))
            return
        }
        toast(ctx, ctx.getString(R.string.update_checking))
        UpdateChecker.checkAsync(ctx) { release ->
            if (activity.isFinishing || activity.isDestroyed) return@checkAsync
            val current = UpdateChecker.currentVersion(ctx)
            when {
                release == null -> toast(ctx, ctx.getString(R.string.update_failed_generic))
                !UpdateChecker.isNewer(release.version, current) ->
                    toast(ctx, ctx.getString(R.string.update_up_to_date, current))
                else -> showUpdateDialog(activity, release)
            }
        }
    }

    fun showUpdateDialog(activity: Activity, release: UpdateChecker.Release) {
        if (release.apkUrl.isBlank()) {
            toast(activity, activity.getString(R.string.update_no_apk_in_release))
            return
        }
        val message = release.notes.ifBlank {
            activity.getString(
                R.string.update_installed_version,
                UpdateChecker.currentVersion(activity),
            )
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(
                activity.getString(R.string.update_available_dialog_title, release.version),
            )
            .setMessage(message)
            .setPositiveButton(R.string.update_now) { _, _ -> startDownload(activity, release) }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    /**
     * Android 8+ requires the user to allow "install unknown apps" for this app once. Explain
     * that instead of dropping the user into a system screen without context.
     */
    private fun startDownload(activity: Activity, release: UpdateChecker.Release) {
        if (!UpdateChecker.canInstallPackages(activity)) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.update_install_dialog_title)
                .setMessage(R.string.update_install_dialog_text)
                .setPositiveButton(R.string.update_open_settings) { _, _ ->
                    UpdateChecker.openInstallPermissionSettings(activity)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        val existing = UpdateChecker.apkFile(activity, release.version)
        if (existing.exists() && UpdateChecker.verifyApk(activity, existing) == null) {
            // Already downloaded (e.g. the user cancelled the install prompt): do not fetch it
            // a second time, go straight to the install step.
            showInstallPrompt(activity, existing)
            return
        }
        if (UpdateChecker.isDownloadRunning(activity)) {
            // Do not enqueue the same file twice: the DownloadManager would fail the second one
            // and the user would see a pointless error notification.
            toast(activity, activity.getString(R.string.update_download_started))
            return
        }
        UpdateChecker.startDownload(activity, release)
        toast(activity, activity.getString(R.string.update_download_started))
    }

    /** Shows the install prompt for an APK that the DownloadManager already fetched. */
    fun showInstallPrompt(activity: Activity, file: File) {
        val version = UpdateChecker.pendingDownloadVersion(activity) ?: ""
        val problem = UpdateChecker.verifyApk(activity, file)
        if (problem != null) {
            file.delete()
            UpdateChecker.clearDownload(activity)
            toast(activity, activity.getString(R.string.update_verified_failed, problem))
            return
        }
        if (!UpdateChecker.canInstallPackages(activity)) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.update_install_dialog_title)
                .setMessage(R.string.update_install_dialog_text)
                .setPositiveButton(R.string.update_open_settings) { _, _ ->
                    UpdateChecker.openInstallPermissionSettings(activity)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_ready_title)
            .setMessage(activity.getString(R.string.update_ready_text, version))
            .setPositiveButton(R.string.update_install_now) { _, _ ->
                UpdateChecker.installApk(activity, file)
            }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun toast(activity: Activity, message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }
}

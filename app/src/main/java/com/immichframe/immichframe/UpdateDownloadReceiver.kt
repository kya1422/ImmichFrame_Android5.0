package com.immichframe.immichframe

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File

/**
 * Receives `ACTION_DOWNLOAD_COMPLETE` from the DownloadManager and turns a finished APK download
 * into an install prompt.
 *
 * The DownloadManager keeps downloading when the frame activity is recreated (or killed while the
 * screen is off), so this receiver - not the activity - is the reliable place to finish the job.
 */
class UpdateDownloadReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return

        val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        // A download that this app did not start (or an old id) is ignored. The broadcast is
        // exported because the system sends it, so never trust the id blindly.
        if (downloadId < 0 || downloadId != UpdateChecker.pendingDownloadId(context)) return

        val version = UpdateChecker.pendingDownloadVersion(context)
        val file = version?.let { UpdateChecker.apkFile(context, it) }

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val status = currentStatus(manager, downloadId)
        FrameLog.i("UpdateDownloadReceiver", "Download $downloadId finished with status $status")

        if (status != DownloadManager.STATUS_SUCCESSFUL) {
            UpdateChecker.clearDownload(context)
            file?.delete()
            UpdateChecker.notifyDownloadFailed(
                context,
                reasonFor(context, manager, downloadId) ?: context.getString(R.string.update_failed_generic),
            )
            return
        }

        if (file == null || version == null) {
            UpdateChecker.clearDownload(context)
            return
        }

        val problem = UpdateChecker.verifyApk(context, file)
        if (problem != null) {
            file.delete()
            UpdateChecker.clearDownload(context)
            UpdateChecker.notifyDownloadFailed(context, problem)
            return
        }

        FrameLog.i("UpdateDownloadReceiver", "APK ${file.name} verified, offering the install prompt")
        UpdateChecker.notifyReadyToInstall(context, file, version)
    }

    private fun currentStatus(manager: DownloadManager, id: Long): Int {
        val cursor = manager.query(DownloadManager.Query().setFilterById(id)) ?: return -1
        cursor.use {
            if (!it.moveToFirst()) return -1
            val index = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
            return if (index >= 0) it.getInt(index) else -1
        }
    }

    private fun reasonFor(context: Context, manager: DownloadManager, id: Long): String? {
        val cursor = manager.query(DownloadManager.Query().setFilterById(id)) ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            val index = it.getColumnIndex(DownloadManager.COLUMN_REASON)
            if (index < 0) return null
            return context.getString(R.string.update_failed_reason, it.getInt(index))
        }
    }
}

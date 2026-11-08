package com.immichframe.immichframe

import android.app.DownloadManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.preference.PreferenceManager
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * In-app update support.
 *
 * The APKs for this fork are published as GitHub Releases, so the app can look at the newest
 * release itself instead of asking the user to sideload a file from a browser. The flow is:
 *
 *  1. [checkAsync] asks the GitHub API for the latest release and compares its tag with the
 *     version of the running app.
 *  2. [startDownload] hands the APK URL to the DownloadManager, which keeps downloading even if
 *     the frame activity is recreated and shows the progress in the notification shade.
 *  3. [UpdateDownloadReceiver] notices the finished download, verifies that the APK is signed
 *     with the *same* key as the installed app and offers the install prompt.
 *
 * The signature check is the important part: the install prompt is safe to accept only because a
 * tampered download (bad mirror, proxy, corrupted file) would be rejected by Android anyway - but
 * we refuse to even offer it, and we say why.
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"

    /** Latest release of this fork. Unauthenticated GitHub API calls are rate limited, but a
     *  once-a-day check is far below the limit. */
    const val RELEASES_API =
        "https://api.github.com/repos/kya1422/ImmichFrame_Android5.0/releases/latest"

    private const val PREFS = "immichframe_update"
    private const val PREF_DOWNLOAD_ID = "download_id"
    private const val PREF_DOWNLOAD_VERSION = "download_version"
    private const val PREF_LATEST_JSON = "latest_json"
    private const val PREF_LAST_CHECK = "last_check"
    private const val PREF_NOTIFIED_VERSION = "notified_version"

    /** Auto check at most once a day: often enough to notice a release, cheap enough for a frame
     *  that runs 24/7 on a metered connection. */
    const val AUTO_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    const val CHANNEL_UPDATES = "updates"

    /** Extra understood by MainActivity: path of a verified APK that should be installed. */
    const val EXTRA_INSTALL_APK = "immichframe.install_apk"
    /** Extra understood by MainActivity: show the "update available" dialog again. */
    const val EXTRA_SHOW_UPDATE = "immichframe.show_update"

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    data class Release(
        val tag: String,
        val version: String,
        val notes: String,
        val apkUrl: String,
        val pageUrl: String,
    ) {
        fun toJson(): String = JSONObject()
            .put("tag", tag)
            .put("version", version)
            .put("notes", notes)
            .put("apkUrl", apkUrl)
            .put("pageUrl", pageUrl)
            .toString()

        companion object {
            fun fromJson(json: String?): Release? {
                if (json.isNullOrBlank()) return null
                return try {
                    val o = JSONObject(json)
                    Release(
                        tag = o.optString("tag"),
                        version = o.optString("version"),
                        notes = o.optString("notes"),
                        apkUrl = o.optString("apkUrl"),
                        pageUrl = o.optString("pageUrl"),
                    )
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    // --- version handling -----------------------------------------------------

    fun currentVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
    } catch (e: Exception) {
        "0"
    }

    /** "v1.0.55.0" / "1.0.55.0" -> [1, 0, 55, 0]. Non numeric parts are ignored. */
    fun parseVersion(version: String): List<Int> =
        version.trim().removePrefix("v").removePrefix("V")
            .split('.', '-', '_', ' ')
            .mapNotNull { it.takeWhile(Char::isDigit).toIntOrNull() }

    fun isNewer(remote: String, current: String): Boolean {
        val r = parseVersion(remote)
        val c = parseVersion(current)
        if (r.isEmpty()) return false
        for (i in 0 until maxOf(r.size, c.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    // --- network --------------------------------------------------------------

    /** Runs the API call off the main thread; the callback is delivered on the main thread. */
    fun checkAsync(context: Context, callback: (Release?) -> Unit) {
        val app = context.applicationContext
        executor.execute {
            val release = try {
                fetchLatestBlocking(app)
            } catch (e: Exception) {
                FrameLog.w(TAG, "Update check failed: ${e.message}")
                null
            }
            // Store the result where the readers look for it ("immichframe_update"), and record
            // the attempt even when it failed - otherwise an unreachable server would make the
            // daily guard useless and the check would run on every resume.
            val stored = prefs(app).edit()
                .putLong(PREF_LAST_CHECK, System.currentTimeMillis())
            if (release != null) stored.putString(PREF_LATEST_JSON, release.toJson())
            stored.apply()
            mainHandler.post { callback(release) }
        }
    }

    private fun fetchLatestBlocking(context: Context): Release? {
        if (!isOnline(context)) {
            FrameLog.i(TAG, "Update check skipped: no network")
            return null
        }
        val connection = (URL(RELEASES_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "ImmichFrame-Android/${currentVersion(context)}")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                FrameLog.w(TAG, "GitHub API returned HTTP $code")
                return null
            }
            val body = BufferedReader(InputStreamReader(connection.inputStream, "UTF-8")).use {
                it.readText()
            }
            return parseRelease(body)
        } finally {
            connection.disconnect()
        }
    }

    fun parseRelease(body: String): Release? {
        val o = JSONObject(body)
        if (o.optBoolean("draft", false) || o.optBoolean("prerelease", false)) return null
        val tag = o.optString("tag_name").ifBlank { return null }
        val version = tag.removePrefix("v")
        val assets = o.optJSONArray("assets")
        var apkUrl = ""
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name")
                if (name.endsWith(".apk")) {
                    apkUrl = a.optString("browser_download_url")
                    if (name == "app-debug.apk") break
                }
            }
        }
        val notes = o.optString("body").trim().take(2000)
        val page = o.optString("html_url")
        return Release(tag = tag, version = version, notes = notes, apkUrl = apkUrl, pageUrl = page)
    }

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }
    }

    // --- download -------------------------------------------------------------

    /** Folder inside the app's external files dir; no storage permission is needed there. */
    fun apkFile(context: Context, version: String): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        return File(dir, "immichframe-$version.apk")
    }

    fun startDownload(context: Context, release: Release): Long {
        release.apkFileCleanup(context)
        val request = DownloadManager.Request(Uri.parse(release.apkUrl)).apply {
            setTitle("ImmichFrame ${release.version}")
            setDescription("APK")
            setMimeType("application/vnd.android.package-archive")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                apkFile(context, release.version).name,
            )
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
        }
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = dm.enqueue(request)
        prefs(context).edit()
            .putLong(PREF_DOWNLOAD_ID, id)
            .putString(PREF_DOWNLOAD_VERSION, release.version)
            .apply()
        FrameLog.i(TAG, "Downloading ${release.version} from ${release.apkUrl} (id $id)")
        return id
    }

    private fun Release.apkFileCleanup(context: Context) {
        // A previous partial file with the same name would make the DownloadManager fail.
        runCatching { apkFile(context, version).delete() }
    }

    fun pendingDownloadId(context: Context): Long =
        prefs(context).getLong(PREF_DOWNLOAD_ID, -1L)

    /** True while the DownloadManager is still fetching an APK started by this app. */
    fun isDownloadRunning(context: Context): Boolean {
        val id = pendingDownloadId(context)
        if (id < 0) return false
        return try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val cursor = dm.query(DownloadManager.Query().setFilterById(id))
            val running = cursor?.use { c ->
                if (!c.moveToFirst()) return false
                val index = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val status = if (index >= 0) c.getInt(index) else -1
                status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING ||
                    status == DownloadManager.STATUS_PAUSED
            } ?: false
            running
        } catch (e: Exception) {
            false
        }
    }

    fun pendingDownloadVersion(context: Context): String? =
        prefs(context).getString(PREF_DOWNLOAD_VERSION, null)

    /**
     * Deletes APKs from earlier update runs (including one that was already installed) and drops
     * the stored download id. Called on process start; a download in flight is left alone.
     */
    fun cleanupOldDownloads(context: Context) {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val pending = pendingDownloadVersion(context)
        val installed = currentVersion(context)

        // The update went through: this process already runs the downloaded version.
        if (pending != null && pending == installed) {
            FrameLog.i(TAG, "Update $pending is installed, removing the APK")
            clearDownload(context)
            dir?.listFiles()?.forEach { if (it.name.endsWith(".apk")) it.delete() }
            return
        }

        // A download that is still running must not be touched.
        if (isDownloadRunning(context)) return

        dir?.listFiles()?.forEach { file ->
            val keep = pending != null && file.name == "immichframe-$pending.apk"
            if (!keep && file.name.endsWith(".apk")) {
                FrameLog.i(TAG, "Removing leftover update file ${file.name}")
                file.delete()
            }
        }
    }

    fun clearDownload(context: Context) {
        prefs(context).edit()
            .remove(PREF_DOWNLOAD_ID)
            .remove(PREF_DOWNLOAD_VERSION)
            .apply()
    }

    // --- verification ---------------------------------------------------------

    /**
     * Returns null when [file] is a genuine ImmichFrame APK signed with the same key as the
     * installed app, otherwise a human readable reason.
     */
    fun verifyApk(context: Context, file: File): String? {
        if (!file.exists() || file.length() == 0L) return "ダウンロードしたファイルが見つかりません"
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val archive = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)
            ?: return "APK を読み取れませんでした（壊れている可能性があります）"
        if (archive.packageName != context.packageName) {
            return "パッケージ名が一致しません (${archive.packageName})"
        }
        @Suppress("DEPRECATION")
        val apkSignature = archive.signatures?.firstOrNull()?.toByteArray()
            ?: return "APK に署名がありません"
        @Suppress("DEPRECATION")
        val ownSignature = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            .signatures?.firstOrNull()?.toByteArray()
            ?: return "現在のアプリの署名を取得できません"
        val apkDigest = MessageDigest.getInstance("SHA-256").digest(apkSignature)
        val ownDigest = MessageDigest.getInstance("SHA-256").digest(ownSignature)
        if (!MessageDigest.isEqual(apkDigest, ownDigest)) {
            return "署名が現在のアプリと一致しません（別の鍵で署名された APK です）"
        }
        return null
    }

    // --- install --------------------------------------------------------------

    /** Android 8+ requires the user to allow installs from this app once. */
    fun canInstallPackages(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            // Before Android 8 the permission is global ("unknown sources"), and the value of
            // INSTALL_NON_MARKET_APPS is not reliable across ROMs. Let the system decide: if the
            // install is blocked it shows its own explanation.
            true
        }

    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }.onFailure {
                // Some Chinese ROMs (EMUI) use their own screen; fall back to the list.
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        } else {
            // No per-app switch before Android 8: send the user to the global security screen.
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_SECURITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    /** Launches the package installer for a verified APK. */
    fun installApk(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        FrameLog.i(TAG, "Install prompt shown for ${file.name}")
    }

    // --- notifications --------------------------------------------------------

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(android.app.NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_UPDATES) != null) return
        val channel = android.app.NotificationChannel(
            CHANNEL_UPDATES,
            context.getString(R.string.update_channel_name),
            android.app.NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.update_channel_description)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    private fun canPostNotifications(context: Context): Boolean {
        // POST_NOTIFICATIONS only exists from API 33; on the devices this fork targets (5.1 / 9)
        // notifications need no runtime permission.
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun notifyUpdateAvailable(context: Context, release: Release) {
        ensureChannel(context)
        val prefs = prefs(context)
        if (prefs.getString(PREF_NOTIFIED_VERSION, null) == release.version) return
        if (!canPostNotifications(context)) return

        val intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_SHOW_UPDATE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pending = PendingIntent.getActivity(context, 1001, intent, flags)

        val notification = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_stat_immichframe)
            .setContentTitle(context.getString(R.string.update_available_title, release.version))
            .setContentText(context.getString(R.string.update_available_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(release.notes.take(400)))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_AVAILABLE, notification) }
            .onFailure { FrameLog.w(TAG, "Could not post update notification: ${it.message}") }
        prefs.edit().putString(PREF_NOTIFIED_VERSION, release.version).apply()
        FrameLog.i(TAG, "Update available: ${release.version}")
    }

    fun notifyReadyToInstall(context: Context, file: File, version: String) {
        ensureChannel(context)
        if (!canPostNotifications(context)) return
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_INSTALL_APK, file.absolutePath)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pending = PendingIntent.getActivity(context, 1002, intent, flags)

        val notification = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_stat_immichframe)
            .setContentTitle(context.getString(R.string.update_ready_title))
            .setContentText(context.getString(R.string.update_ready_text, version))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_READY, notification) }
            .onFailure { FrameLog.w(TAG, "Could not post install notification: ${it.message}") }
    }

    fun notifyDownloadFailed(context: Context, reason: String) {
        ensureChannel(context)
        if (!canPostNotifications(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_stat_immichframe)
            .setContentTitle(context.getString(R.string.update_failed_title))
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_READY, notification) }
        FrameLog.w(TAG, "Update download failed: $reason")
    }

    const val NOTIFICATION_ID_AVAILABLE = 42
    const val NOTIFICATION_ID_READY = 43

    // --- automatic check ------------------------------------------------------

    fun lastCheck(context: Context): Long = prefs(context).getLong(PREF_LAST_CHECK, 0L)

    /** Called when the frame comes to the foreground; does real work at most once a day. */
    fun maybeAutoCheck(context: Context) {
        if (!autoCheckEnabled(context)) return
        val now = System.currentTimeMillis()
        if (now - lastCheck(context) < AUTO_CHECK_INTERVAL_MS) return
        checkAsync(context) { release ->
            if (release == null) return@checkAsync
            val current = currentVersion(context)
            if (isNewer(release.version, current)) notifyUpdateAvailable(context, release)
        }
    }

    fun autoCheckEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean("autoUpdateCheck", true)

    fun latestKnownRelease(context: Context): Release? =
        Release.fromJson(prefs(context).getString(PREF_LATEST_JSON, null))

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

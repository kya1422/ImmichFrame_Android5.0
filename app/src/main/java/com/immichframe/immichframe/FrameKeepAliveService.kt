package com.immichframe.immichframe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/**
 * Optional foreground service that keeps the frame process alive and re-evaluates the Active
 * Times schedule on a fixed interval.
 *
 * Huawei's EMUI (the d-01H is EMUI 3.1 on top of Android 5.1) and other vendor skins kill
 * backgrounded apps when the screen turns off, which takes the scheduled wake-up alarm with it:
 * the frame then stays dark until somebody touches it. A foreground service with its own
 * notification is the platform-sanctioned way to stay alive, and the watchdog additionally
 * re-arms the alarm if the system ever drops it, plus wakes the activity itself if the alarm
 * turns out not to be delivered on that device.
 *
 * The service stops itself outside of the configured active hours so the device can sleep.
 */
class FrameKeepAliveService : Service() {
    companion object {
        private const val TAG = "keepalive"
        private const val CHANNEL_ID = "immichframe_keepalive"
        private const val NOTIFICATION_ID = 4711
        private const val CHECK_INTERVAL_MS = 60_000L
        private const val WAKE_GRACE_MS = 90_000L
        private const val HEARTBEAT_INTERVAL_MS = 10 * 60_000L
        private const val DUPLICATE_WAKE_GUARD_MS = 60_000L

        const val EXTRA_TEST = "extra_test"
        const val TEST_DURATION_MS = 3 * 60_000L

        fun start(context: Context) {
            try {
                context.startService(Intent(context, FrameKeepAliveService::class.java))
            } catch (e: Exception) {
                FrameLog.w(TAG, "Could not start the keep-alive service: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, FrameKeepAliveService::class.java))
            } catch (e: Exception) {
                FrameLog.w(TAG, "Could not stop the keep-alive service: ${e.message}")
            }
        }

        fun isEnabledInSettings(context: Context): Boolean =
            androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(context.applicationContext)
                .getBoolean("keepAlive", false)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastHeartbeat = 0L
    private var testUntil = 0L
    private var stopping = false

    private val watchdog = object : Runnable {
        override fun run() {
            if (stopping) return
            evaluate()
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        FrameLog.init(applicationContext)
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireWakeLock()
        handler.post(watchdog)
        FrameLog.i(TAG, "Keep-alive started (SDK ${Build.VERSION.SDK_INT})")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_TEST, false) == true) {
            testUntil = System.currentTimeMillis() + TEST_DURATION_MS
            FrameLog.i(TAG, "Diagnostics test mode: staying awake for 3 minutes")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopping = true
        handler.removeCallbacksAndMessages(null)
        releaseWakeLock()
        FrameLog.i(TAG, "Keep-alive stopped")
        super.onDestroy()
    }

    // --- core loop ----------------------------------------------------------

    private fun evaluate() {
        val now = System.currentTimeMillis()
        val inTest = now < testUntil

        // The diagnostics self-test may use this service as a wake-up crutch even when the user
        // has not enabled it permanently.
        if (!inTest && !FrameKeepAliveService.isEnabledInSettings(this)) {
            FrameLog.i(TAG, "Keep-alive disabled in the settings, stopping")
            stopSelf()
            return
        }

        val activeTimes = ActiveScheduleManager.activeTimesEnabled(this)

        if (!activeTimes || inTest) {
            ActiveScheduleManager.cancelWakeAlarm(this)
            if (inTest) FrameLog.i(TAG, "Test mode active, ignoring the schedule")
            maybeHeartbeat(now)
            return
        }

        val shouldBeActive = ActiveScheduleManager.shouldBeActiveNow(this)
        val pending = ActiveScheduleManager.pendingAlarm(this)

        if (shouldBeActive) {
            if (pending == null || pending < now) {
                // Still inside the active period: make sure the screen is on. This covers devices
                // where the wake alarm never arrives, and the case where the device booted during
                // the active hours before the user unlocked it.
                FrameLog.i(
                    TAG,
                    "Active period, no pending alarm -> waking the frame" +
                            (if (pending != null) " (stale alarm ${ActiveScheduleManager.format(pending)})" else "")
                )
                wakeFrame()
            } else if (pending - now < WAKE_GRACE_MS) {
                FrameLog.w(
                    TAG,
                    "Alarm was due/close (${ActiveScheduleManager.format(pending)}) but the frame is still asleep"
                )
                wakeFrame()
            }
        } else {
            // Inactive period: the process has to stay alive for the wake-up alarm, which is
            // exactly what EMUI likes to destroy. Re-arm it when it is missing.
            if (pending == null || pending < now) {
                ActiveScheduleManager.armWakeAlarm(this)
            }
        }

        maybeHeartbeat(now)
    }

    private fun maybeHeartbeat(now: Long) {
        if (now - lastHeartbeat < HEARTBEAT_INTERVAL_MS) return
        lastHeartbeat = now
        FrameLog.i(TAG, "Heartbeat (process alive)")
    }

    private fun wakeFrame() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                            or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            startActivity(intent)
        } catch (e: Exception) {
            FrameLog.w(TAG, "Could not bring the frame to the foreground: ${e.message}")
        }
    }

    // --- foreground plumbing ------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.keepalive_channel_name),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = getString(R.string.keepalive_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.keepalive_notification_title))
                .setContentText(getString(R.string.keepalive_notification_text))
                .setSmallIcon(R.mipmap.immich_frame)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(getString(R.string.keepalive_notification_title))
                .setContentText(getString(R.string.keepalive_notification_text))
                .setSmallIcon(R.mipmap.immich_frame)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .build()
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "immichframe:keepalive",
            ).apply { acquire() }
        } catch (e: Exception) {
            FrameLog.w(TAG, "Could not acquire the wake lock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
    }
}

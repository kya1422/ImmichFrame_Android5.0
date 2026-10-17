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
import androidx.core.content.ContextCompat

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
 * Behaviour: while the frame is supposed to be on it holds a partial wake lock and only starts
 * MainActivity when it is not running; outside the active hours it releases the wake lock (so the
 * device can sleep) and just keeps the wake-up alarm armed. It stops itself when the feature is
 * disabled or Active Times is turned off.
 */
class FrameKeepAliveService : Service() {
    companion object {
        private const val TAG = "keepalive"
        private const val CHANNEL_ID = "immichframe_keepalive"
        private const val NOTIFICATION_ID = 4711
        private const val CHECK_INTERVAL_MS = 60_000L
        private const val HEARTBEAT_INTERVAL_MS = 10 * 60_000L

        const val EXTRA_TEST = "extra_test"
        const val TEST_DURATION_MS = 3 * 60_000L

        fun start(context: Context) {
            try {
                // Android 8+ forbids starting a background service, and this one is also started
                // from BOOT_COMPLETED. startForegroundService() is the supported way; the service
                // calls startForeground() in onCreate() within the 5 s window.
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, FrameKeepAliveService::class.java),
                )
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

    /** Previous schedule state, used to notice the transition into the sleep window. */
    private var lastShouldBeActive: Boolean? = null
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
        // The wake lock is taken by evaluate() only while the frame is supposed to be on.
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

        if (!ActiveScheduleManager.activeTimesEnabled(this)) {
            if (!inTest) {
                // Nothing to keep alive: with Active Times off the frame is always on and the
                // activity itself keeps the screen awake. Staying here would hold a wake lock
                // forever and drain the battery.
                FrameLog.i(TAG, "Active Times disabled, stopping the keep-alive service")
                stopSelf()
                return
            }
            ActiveScheduleManager.cancelWakeAlarm(this)
            setWakeLockHeld(true)
            FrameLog.i(TAG, "Test mode active, ignoring the schedule")
            maybeHeartbeat(now)
            return
        }

        val shouldBeActiveNow = ActiveScheduleManager.shouldBeActiveNow(this)
        if (shouldBeActiveNow) {
            // Active hours: hold the CPU awake so the frame keeps rendering and the periodic
            // check in MainActivity keeps running.
            setWakeLockHeld(true)
            // Only start the activity when it is actually gone. (This used to launch it every
            // minute, because "no pending alarm" is the normal state while the frame is active -
            // which pulled the user out of the settings and diagnostics screens.)
            if (!MainActivity.isFrameRunning) {
                FrameLog.i(TAG, "Active period but the frame is not running -> starting it")
                wakeFrame()
            }
        } else {
            // Sleep window: the process has to stay alive for the wake-up alarm (exactly what
            // EMUI likes to destroy), but the CPU may sleep again. So the wake lock is released
            // and only the alarm is kept armed.
            setWakeLockHeld(false)
            val armedAt = ActiveScheduleManager.armedAt(this)
            if (armedAt == null || armedAt < now) {
                ActiveScheduleManager.armWakeAlarm(this)
            }
        }

        // If the active period just ended, make sure the screen goes off even when MainActivity
        // was killed by the system and its own 30 second check is not running any more.
        if (lastShouldBeActive == true && !shouldBeActiveNow) {
            FrameLog.i(TAG, "Active period ended -> asking the frame to sleep")
            sendBroadcast(
                Intent(ScheduleWakeReceiver.ACTION_FRAME_COMMAND)
                    .putExtra(ScheduleWakeReceiver.EXTRA_FRAME_ACTIVE, false)
            )
        }
        lastShouldBeActive = shouldBeActiveNow

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
                .setSmallIcon(R.drawable.ic_stat_immichframe)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(getString(R.string.keepalive_notification_title))
                .setContentText(getString(R.string.keepalive_notification_text))
                .setSmallIcon(R.drawable.ic_stat_immichframe)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .build()
        }
    }

    /**
     * Holds/releases the partial wake lock. It is only held while the frame is supposed to be on
     * (and during the diagnostics self-test); outside the active hours the device must be able to
     * sleep, otherwise the battery is drained all night. The service itself stays in the
     * foreground either way, so vendor task killers still leave the process alone.
     */
    private fun setWakeLockHeld(hold: Boolean) {
        try {
            if (hold) {
                if (wakeLock?.isHeld != true) {
                    val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                    wakeLock = powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "immichframe:keepalive",
                    ).apply { acquire() }
                    FrameLog.i(TAG, "Wake lock acquired")
                }
            } else if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                wakeLock = null
                FrameLog.i(TAG, "Wake lock released (outside the active hours)")
            }
        } catch (e: Exception) {
            FrameLog.w(TAG, "Could not update the wake lock: ${e.message}")
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

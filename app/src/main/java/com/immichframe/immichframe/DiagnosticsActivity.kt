package com.immichframe.immichframe

import android.app.Activity
import android.app.admin.DeviceAdminInfo
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Field diagnostics for the Active Times / screen-off feature.
 *
 * The frame is normally used somewhere without a PC, so this screen answers the usual questions
 * without logcat: is the feature enabled, what schedule is stored, does the device admin actually
 * have the force-lock policy, is a wake-up alarm armed, is the process alive, what does the event
 * log say - plus buttons that test the whole chain on the spot.
 */
class DiagnosticsActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var activeTimesCheckBox: CheckBox
    private val handler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var suppressCheckBoxCallback = false

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FrameLog.init(applicationContext)
        setContentView(R.layout.diagnostics_view)

        statusText = findViewById(R.id.diagStatusText)
        logText = findViewById(R.id.diagLogText)
        activeTimesCheckBox = findViewById(R.id.chkDiagActive)

        findViewById<Button>(R.id.btnClose).setOnClickListener { finish() }

        findViewById<Button>(R.id.btnCollect).setOnClickListener {
            startActivity(Intent.createChooser(shareIntent(), getString(R.string.diag_share)))
        }

        activeTimesCheckBox.setOnCheckedChangeListener { _, checked ->
            if (suppressCheckBoxCallback) return@setOnCheckedChangeListener
            PreferenceManager.getDefaultSharedPreferences(applicationContext)
                .edit().putBoolean("activeTimes", checked).apply()
            FrameLog.i("diag", "Active Times set to $checked from the diagnostics screen")
            if (checked) {
                if (FrameKeepAliveService.isEnabledInSettings(applicationContext)) {
                    FrameKeepAliveService.start(applicationContext)
                }
                ActiveScheduleManager.armWakeAlarm(applicationContext)
            } else {
                ActiveScheduleManager.cancelWakeAlarm(applicationContext)
                FrameKeepAliveService.stop(applicationContext)
            }
            refresh()
        }

        findViewById<Button>(R.id.btnArmMinute).setOnClickListener {
            val whenAt = ActiveScheduleManager.armTestAlarm(this, 60_000L)
            Toast.makeText(
                this,
                getString(R.string.diag_armed_toast, ActiveScheduleManager.format(whenAt)),
                Toast.LENGTH_LONG,
            ).show()
            refresh()
        }

        findViewById<Button>(R.id.btnTestNow).setOnClickListener { runWakeTest() }

        findViewById<Button>(R.id.btnRecheck).setOnClickListener {
            FrameLog.i("diag", "Manual check requested")
            val context = applicationContext
            if (ActiveScheduleManager.shouldBeActiveNow(context)) {
                ActiveScheduleManager.cancelWakeAlarm(context)
                startActivity(
                    Intent(context, MainActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK
                                    or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                                    or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        )
                    }
                )
            } else {
                ActiveScheduleManager.armWakeAlarm(context)
            }
            refresh()
        }

        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            FrameLog.clear(this)
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    // --- actions ------------------------------------------------------------

    /**
     * Tests the complete chain while the user is standing in front of the device: sleep the frame
     * now, wake it again 30 seconds later through the alarm + receiver, with the keep-alive service
     * temporarily kept awake so a sleeping device cannot hide a broken alarm.
     */
    private fun runWakeTest() {
        // Note: the user's settings are not modified. Sleeping and waking both use the explicit
        // command path, so the test works with Active Times on or off.
        FrameLog.i("diag", "Wake test started: sleeping now, waking in 30 s")
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, FrameKeepAliveService::class.java)
                    .putExtra(FrameKeepAliveService.EXTRA_TEST, true)
            )
        } catch (e: Exception) {
            FrameLog.w("diag", "Could not start the keep-alive service for the test: ${e.message}")
        }
        ActiveScheduleManager.armTestAlarm(this, 30_000L)

        // Explicit intent: implicit broadcasts no longer reach manifest receivers on Android 8+,
        // so the previous version silently did nothing on newer devices.
        sendBroadcast(
            Intent(this, ScheduleWakeReceiver::class.java).apply {
                action = ScheduleWakeReceiver.ACTION_WAKE
                putExtra(ScheduleWakeReceiver.EXTRA_FORCE_SLEEP, true)
            }
        )
        Toast.makeText(this, getString(R.string.diag_test_started), Toast.LENGTH_LONG).show()
    }

    private fun shareIntent(): Intent {
        val text = buildString {
            append("ImmichFrame diagnostics ")
            append(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
            append("\n\n")
            append(statusText.text)
            append("\n--- event log ---\n")
            append(FrameLog.read(this@DiagnosticsActivity))
        }
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "ImmichFrame diagnostics")
            putExtra(Intent.EXTRA_TEXT, text)
        }
    }

    // --- status -------------------------------------------------------------

    private fun refresh() {
        val context = applicationContext
        val now = Calendar.getInstance()
        val activeTimes = ActiveScheduleManager.activeTimesEnabled(context)
        val schedule = ActiveScheduleManager.schedule(context)
        val shouldBeActive = ActiveScheduleManager.shouldBeActiveNow(context, now)
        val next = if (activeTimes) ActiveScheduleManager.nextTransition(context, now) else null
        val pendingAlarm = ActiveScheduleManager.pendingAlarm(context)
        val serviceEnabled = FrameKeepAliveService.isEnabledInSettings(context)

        suppressCheckBoxCallback = true
        activeTimesCheckBox.isChecked = activeTimes
        suppressCheckBoxCallback = false

        val builder = StringBuilder()
        builder.append("Now: ").append(ActiveScheduleManager.format(now.timeInMillis))
            .append("   (screen updates at ").append(timeFormat.format(Date())).append(")\n")
        builder.append("Active Times: ").append(if (activeTimes) "ENABLED" else "DISABLED")
            .append("  -> frame should be ").append(if (shouldBeActive) "ON" else "OFF").append("\n")
        builder.append("Frame process: ")
            .append(if (MainActivity.isFrameRunning) "running" else "NOT running")
            .append("\n")

        builder.append("\nSchedule rules: ")
        if (schedule.rules.isEmpty()) {
            builder.append("none (always active)\n")
        } else {
            builder.append("\n")
            schedule.rules.forEach { rule ->
                builder.append("  ").append(dayNames(rule.days)).append(": ")
                    .append(rule.ranges.joinToString(", ") { "${it.start}-${it.end}" })
                    .append("\n")
            }
        }

        builder.append("\nNext transition: ")
        if (next == null) {
            builder.append("none\n")
        } else {
            builder.append(ActiveScheduleManager.format(next.at.timeInMillis))
                .append(if (next.becomesActive) "  (wake up)" else "  (sleep)")
                .append("  in ").append(minutesUntil(next.at.timeInMillis)).append("\n")
        }

        val armedAt = ActiveScheduleManager.armedAt(context)
        builder.append("Armed wake alarm (ours): ")
            .append(armedAt?.let { ActiveScheduleManager.format(it) } ?: "none")
            .append("\n")
        builder.append("System next alarm clock: ")
            .append(pendingAlarm?.let { ActiveScheduleManager.format(it) } ?: "none")
            .append("   (may belong to another app)\n")

        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val component = FrameDeviceAdminReceiver.componentName(this)
        val adminActive = dpm.isAdminActive(component)
        val forceLockGranted = adminActive &&
                runCatching {
                    dpm.hasGrantedPolicy(component, DeviceAdminInfo.USES_POLICY_FORCE_LOCK)
                }.getOrDefault(false)
        builder.append("\nDevice admin: ").append(if (adminActive) "ACTIVE" else "NOT active")
            .append("   force-lock ").append(if (forceLockGranted) "granted" else "not granted")
            .append("\n")

        builder.append("Keep-alive service: ")
            .append(if (serviceEnabled) "enabled in settings" else "disabled in settings")
            .append("\n")

        val screenTimeout = runCatching {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, -1)
        }.getOrDefault(-1)
        builder.append("Screen timeout: ").append(screenTimeout / 1000).append(" s\n")

        builder.append("Android ").append(Build.VERSION.RELEASE)
            .append(" / API ").append(Build.VERSION.SDK_INT)
            .append(" / ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
            .append("\n")

        statusText.text = builder.toString()
        logText.text = FrameLog.read(this)
    }

    private fun dayNames(days: Set<Int>): String {
        if (days.isEmpty()) return "(no days)"
        val names = listOf(
            Calendar.MONDAY to "Mon",
            Calendar.TUESDAY to "Tue",
            Calendar.WEDNESDAY to "Wed",
            Calendar.THURSDAY to "Thu",
            Calendar.FRIDAY to "Fri",
            Calendar.SATURDAY to "Sat",
            Calendar.SUNDAY to "Sun",
        )
        return names.filter { days.contains(it.first) }.joinToString(",") { it.second }
    }

    private fun minutesUntil(timestamp: Long): String {
        val minutes = (timestamp - System.currentTimeMillis() + 59_999) / 60_000
        return "~$minutes min"
    }
}

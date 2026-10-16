package com.immichframe.immichframe

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.preference.PreferenceManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Central place for the "Active Times" schedule: evaluating it, computing the next transition and
 * arming the wake-up alarm. Used by MainActivity, the screensaver service, the boot receiver and
 * the keep-alive service, so all of them agree on the same state.
 *
 * The wake alarm is armed with [AlarmManager.setAlarmClock] because that is the most reliable way
 * to be woken up:
 *  - available since API 21 (every device this app supports),
 *  - not deferred by Doze or by vendor power savers,
 *  - the pending alarm is visible to the system, so the Diagnostics screen can show whether a
 *    wake-up is actually armed.
 * If the platform rejects it we fall back to setExact() and finally to set().
 */
object ActiveScheduleManager {
    private const val TAG = "schedule"
    private const val REQUEST_CODE = 4711
    private const val TEST_REQUEST_CODE = 4712

    private fun prefs(context: Context) =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    fun activeTimesEnabled(context: Context): Boolean =
        prefs(context).getBoolean("activeTimes", false)

    fun schedule(context: Context): Helpers.ActiveSchedule =
        Helpers.parseActiveSchedule(prefs(context).getString("activeSchedule", null))

    fun shouldBeActiveNow(context: Context, now: Calendar = Calendar.getInstance()): Boolean =
        !activeTimesEnabled(context) || Helpers.isActiveNow(schedule(context), now)

    fun nextTransition(
        context: Context,
        now: Calendar = Calendar.getInstance(),
    ): Helpers.Transition? = Helpers.nextTransition(schedule(context), now)

    fun format(timestamp: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

    fun formatShort(timestamp: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))

    private fun wakePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ScheduleWakeReceiver::class.java).apply {
            action = ScheduleWakeReceiver.ACTION_WAKE
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun showPendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * (Re-)arms the wake alarm for the next moment the frame has to come back on. Returns the
     * wake time in milliseconds, or null when no alarm is needed (feature disabled or the frame
     * is currently supposed to be on, in which case the periodic check handles the sleep side).
     */
    fun armWakeAlarm(context: Context): Long? {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = wakePendingIntent(context)
        // Always drop the previous alarm first so a stale wake time cannot fire.
        alarmManager.cancel(pendingIntent)

        if (!activeTimesEnabled(context)) return null
        if (shouldBeActiveNow(context)) return null

        val next = nextTransition(context) ?: return null
        if (next.becomesActive.not()) {
            // Should not happen (we are inactive, so the next change is the wake-up), but never
            // arm an alarm that would fire while the frame is supposed to sleep.
            return null
        }
        val time = next.at.timeInMillis
        return try {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(time, showPendingIntent(context)),
                pendingIntent,
            )
            FrameLog.i(TAG, "Wake alarm armed for ${format(time)}")
            time
        } catch (e: Exception) {
            FrameLog.w(TAG, "setAlarmClock() rejected (${e.message}); falling back to setExact()")
            try {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, time, pendingIntent)
                FrameLog.i(TAG, "Wake alarm armed for ${format(time)} (setExact)")
                time
            } catch (e2: Exception) {
                alarmManager.set(AlarmManager.RTC_WAKEUP, time, pendingIntent)
                FrameLog.w(TAG, "Wake alarm armed for ${format(time)} (inexact): ${e2.message}")
                time
            }
        }
    }

    fun cancelWakeAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(wakePendingIntent(context))
        FrameLog.i(TAG, "Wake alarm cancelled")
    }

    /**
     * Arms a one-shot alarm that force-activates the frame regardless of the schedule. Used by the
     * Diagnostics screen to verify the whole wake-up chain (alarm -> receiver -> activity ->
     * screen on) while the user is standing in front of the device.
     */
    fun armTestAlarm(context: Context, delayMillis: Long): Long {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val time = System.currentTimeMillis() + delayMillis
        val intent = Intent(context, ScheduleWakeReceiver::class.java).apply {
            action = ScheduleWakeReceiver.ACTION_WAKE
            putExtra(ScheduleWakeReceiver.EXTRA_FORCE_WAKE, true)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            TEST_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.cancel(pendingIntent)
        try {
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(time, null), pendingIntent)
        } catch (e: Exception) {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, time, pendingIntent)
        }
        FrameLog.i(TAG, "Wake test armed for ${format(time)}")
        return time
    }

    /** Trigger time of the next alarm clock known to the system (normally ours). */
    fun pendingAlarm(context: Context): Long? = try {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.nextAlarmClock?.triggerTime
    } catch (e: Exception) {
        null
    }
}

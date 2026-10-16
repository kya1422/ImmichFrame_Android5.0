package com.immichframe.immichframe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fired by AlarmManager at the next scheduled active-start time. Wakes the device by bringing
 * MainActivity to the foreground; MainActivity then re-evaluates the schedule and powers the
 * screen back on. Needed because the device may be in deep sleep, where the in-app Handler loop
 * is suspended.
 *
 * It also serves as the target of the Diagnostics screen self-test:
 *  - [EXTRA_FORCE_WAKE]  -> wake the frame regardless of the schedule
 *  - [EXTRA_FORCE_SLEEP] -> sleep the frame regardless of the schedule
 */
class ScheduleWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        FrameLog.init(context.applicationContext)
        when {
            intent?.getBooleanExtra(EXTRA_FORCE_SLEEP, false) == true -> {
                FrameLog.i("wake", "Test sleep requested")
                context.sendBroadcast(
                    Intent(ACTION_FRAME_COMMAND).putExtra(EXTRA_FRAME_ACTIVE, false)
                )
            }

            intent?.getBooleanExtra(EXTRA_FORCE_WAKE, false) == true -> {
                FrameLog.i("wake", "Test wake requested")
                context.sendBroadcast(
                    Intent(ACTION_FRAME_COMMAND).putExtra(EXTRA_FRAME_ACTIVE, true)
                )
                launchMainActivity(context)
            }

            else -> {
                FrameLog.i("wake", "Wake alarm delivered")
                launchMainActivity(context)
            }
        }
    }

    private fun launchMainActivity(context: Context) {
        try {
            val launch = Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                            or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            context.startActivity(launch)
        } catch (e: Exception) {
            FrameLog.w("wake", "Could not start MainActivity: ${e.message}")
        }
    }

    companion object {
        const val ACTION_WAKE = "com.immichframe.immichframe.ACTION_SCHEDULE_WAKE"

        /** Internal broadcast that tells a running MainActivity to switch on/off immediately. */
        const val ACTION_FRAME_COMMAND = "com.immichframe.immichframe.ACTION_FRAME_COMMAND"
        const val EXTRA_FRAME_ACTIVE = "frame_active"

        const val EXTRA_FORCE_WAKE = "force_wake"
        const val EXTRA_FORCE_SLEEP = "force_sleep"
    }
}

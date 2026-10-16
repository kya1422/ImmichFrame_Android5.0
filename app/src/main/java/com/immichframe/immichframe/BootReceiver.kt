package com.immichframe.immichframe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-arms everything after a reboot. MainActivity.startAlarmClock() only runs
 * while the app is in the foreground, so booting the frame outside of its active hours used to
 * leave it without a wake-up alarm until somebody opened the settings.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            -> {
                FrameLog.init(context.applicationContext)
                FrameLog.i("boot", "Boot completed, restoring the Active Times state")
                ActiveScheduleManager.armWakeAlarm(context)
                if (FrameKeepAliveService.isEnabledInSettings(context)) {
                    FrameKeepAliveService.start(context)
                }
            }
        }
    }
}

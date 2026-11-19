package com.immichframe.immichframe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-arms everything after a reboot. The schedule logic only runs while the app is in the
 * foreground, so booting the frame outside of its active hours used to leave it without a
 * wake-up alarm until somebody opened the settings.
 *
 * The receiver is exported for the vendor QUICKBOOT_POWERON actions (some firmware sends them
 * from an ordinary app UID); BOOT_COMPLETED itself arrives either way, because the system server
 * is allowed to reach unexported components. Nothing here is privileged: the receiver only
 * re-arms the alarm and starts the keep-alive service, both of which are idempotent.
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

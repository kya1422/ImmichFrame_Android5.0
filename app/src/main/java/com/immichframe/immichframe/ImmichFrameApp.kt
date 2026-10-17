package com.immichframe.immichframe

import android.app.Application

/**
 * Application entry point.
 *
 * The event log is initialised here so that every component (activity, receivers, services) can
 * write to it even when it is the first thing started in a new process. The schedule alarm is
 * re-armed early as well: the process may have been started by something else than the activity
 * (e.g. the widget provider) and an alarm that the system dropped otherwise stays missing until
 * the user opens the app.
 */
class ImmichFrameApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FrameLog.init(this)
        FrameLog.i("app", "Process started (SDK ${android.os.Build.VERSION.SDK_INT})")
        try {
            ActiveScheduleManager.armWakeAlarm(this)
        } catch (e: Exception) {
            FrameLog.w("app", "Could not restore the wake alarm: ${e.message}")
        }
    }
}

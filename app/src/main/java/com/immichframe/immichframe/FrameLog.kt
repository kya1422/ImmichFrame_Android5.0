package com.immichframe.immichframe

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small rolling event log kept inside the app.
 *
 * A photo frame usually runs without a PC attached, so "please send me the logcat" is not an
 * option when something goes wrong. This log records the events that matter for the Active Times
 * feature (schedule checks, screen off/on, alarms, boots, heartbeats) and is displayed by the
 * Diagnostics screen. A gap between keep-alive heartbeats inside the active hours means the OS
 * killed the app; outside them the CPU is allowed to sleep, so gaps there are normal.
 */
object FrameLog {
    private const val LOG_TAG = "ImmichFrame"
    private const val FILE_NAME = "frame_log.txt"
    private const val MAX_LINES = 500
    private const val TRIM_EVERY = 32

    private val lock = Any()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    @Volatile
    private var appContext: Context? = null
    private var writesSinceTrim = 0

    /** Called from Application.onCreate(); safe to call repeatedly. */
    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    fun i(tag: String, message: String) {
        Log.i("$LOG_TAG/$tag", message)
        write("$tag: $message")
    }

    fun w(tag: String, message: String) {
        Log.w("$LOG_TAG/$tag", message)
        write("$tag(W): $message")
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private fun write(message: String) {
        val context = appContext ?: return
        synchronized(lock) {
            try {
                file(context).appendText("${timeFormat.format(Date())} $message\n")
                writesSinceTrim++
                if (writesSinceTrim >= TRIM_EVERY) {
                    writesSinceTrim = 0
                    trim(context)
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Could not write the event log: ${e.message}")
            }
        }
    }

    private fun trim(context: Context) {
        val target = file(context)
        val lines = target.readLines()
        if (lines.size <= MAX_LINES) return
        target.writeText(lines.takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
    }

    fun read(context: Context): String = synchronized(lock) {
        try {
            val target = file(context)
            if (!target.exists() || target.length() == 0L) "No events logged yet." else target.readText()
        } catch (e: Exception) {
            "Could not read the event log: ${e.message}"
        }
    }

    fun clear(context: Context) {
        synchronized(lock) {
            try {
                file(context).writeText("")
            } catch (_: Exception) {
                // nothing we can do
            }
        }
    }
}

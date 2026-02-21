package com.openclaw.assistant

import android.app.Application
import com.openclaw.assistant.util.AppLogger
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Application class — sets up global crash handler so stack traces
 * survive the crash and can be read from the in-app log panel.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        setupCrashHandler()
        loadPreviousCrash()
    }

    private val crashFile: File
        get() = File(filesDir, "last_crash.txt")

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val report = "[$timestamp] CRASH on thread '${thread.name}':\n$sw"

                // Write to file so it survives the crash
                crashFile.writeText(report)

                // Also try to log it (may not show if process dies immediately)
                AppLogger.e("CRASH", report.take(500))
            } catch (_: Exception) {
                // Don't let the crash handler itself crash
            }
            // Let the system handle the crash dialog
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * On next launch, load any crash from the previous run into AppLogger
     * so it's visible in the log panel.
     */
    private fun loadPreviousCrash() {
        try {
            if (crashFile.exists()) {
                val text = crashFile.readText()
                if (text.isNotBlank()) {
                    AppLogger.e("CRASH", "⚠️ Previous crash:\n$text")
                }
                // Clear so we don't show it again next time
                crashFile.delete()
            }
        } catch (_: Exception) {}
    }
}

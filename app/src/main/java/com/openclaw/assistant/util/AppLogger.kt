package com.openclaw.assistant.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-process log buffer for the app. Captures key events and errors so they can
 * be reviewed from within the app itself — no logcat or PC required.
 *
 * Keeps the last MAX_ENTRIES entries in memory. Also forwards to Android Log.
 */
object AppLogger {

    private const val MAX_ENTRIES = 300

    enum class Level(val label: String) {
        DEBUG("D"),
        INFO("I"),
        WARN("W"),
        ERROR("E")
    }

    data class LogEntry(
        val timestamp: Long = System.currentTimeMillis(),
        val level: Level,
        val tag: String,
        val message: String
    ) {
        val formattedTime: String
            get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))
    }

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun log(level: Level, tag: String, message: String) {
        val entry = LogEntry(level = level, tag = tag, message = message)
        _entries.update { current ->
            (current + entry).takeLast(MAX_ENTRIES)
        }
        // Forward to system log as well
        when (level) {
            Level.DEBUG -> Log.d(tag, message)
            Level.INFO  -> Log.i(tag, message)
            Level.WARN  -> Log.w(tag, message)
            Level.ERROR -> Log.e(tag, message)
        }
    }

    fun d(tag: String, message: String) = log(Level.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(Level.INFO,  tag, message)
    fun w(tag: String, message: String) = log(Level.WARN,  tag, message)
    fun e(tag: String, message: String) = log(Level.ERROR, tag, message)

    fun clear() = _entries.update { emptyList() }

    /** Export all entries as a plain-text string for clipboard/share. */
    fun export(): String = _entries.value.joinToString("\n") { entry ->
        "${entry.formattedTime} [${entry.level.label}] ${entry.tag}: ${entry.message}"
    }
}

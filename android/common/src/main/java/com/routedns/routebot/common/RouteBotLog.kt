package com.routedns.routebot.common

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RouteBotLog {
    private const val TAG = "RouteBot"
    private const val MAX_LOG_FILE_BYTES = 1_000_000L

    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)

    /** Called once from Application.onCreate() to enable on-device log persistence
     * for the "upload_logs" remote command (PRD §12 structured logs / §9 remote command). */
    fun init(context: Context) {
        logFile = File(context.filesDir, "routebot.log")
    }

    fun getLogFile(): File? = logFile

    fun d(event: String, fields: Map<String, Any?> = emptyMap()) {
        val msg = format(event, fields)
        Log.d(TAG, msg)
        appendToFile("D", msg)
    }

    fun i(event: String, fields: Map<String, Any?> = emptyMap()) {
        val msg = format(event, fields)
        Log.i(TAG, msg)
        appendToFile("I", msg)
    }

    fun w(event: String, fields: Map<String, Any?> = emptyMap(), throwable: Throwable? = null) {
        val msg = format(event, fields)
        Log.w(TAG, msg, throwable)
        appendToFile("W", msg, throwable)
    }

    fun e(event: String, fields: Map<String, Any?> = emptyMap(), throwable: Throwable? = null) {
        val msg = format(event, fields)
        Log.e(TAG, msg, throwable)
        appendToFile("E", msg, throwable)
    }

    private fun format(event: String, fields: Map<String, Any?>): String {
        if (fields.isEmpty()) return event
        val sanitized = fields
            .filterKeys { key ->
                !key.contains("api_key", ignoreCase = true) &&
                    !key.contains("token", ignoreCase = true) &&
                    !key.contains("secret", ignoreCase = true) &&
                    !key.contains("password", ignoreCase = true)
            }
            .entries
            .joinToString(" ") { (k, v) -> "$k=$v" }
        return "$event $sanitized"
    }

    @Synchronized
    private fun appendToFile(level: String, message: String, throwable: Throwable? = null) {
        val file = logFile ?: return
        try {
            if (file.exists() && file.length() > MAX_LOG_FILE_BYTES) {
                file.writeText("") // simple rotation: truncate when the cap is hit
            }
            val ts = dateFormat.format(Date())
            file.appendText("$ts [$level] $message\n")
            if (throwable != null) {
                file.appendText(throwable.stackTraceToString() + "\n")
            }
        } catch (_: Exception) {
            // Never let logging itself crash the app.
        }
    }
}

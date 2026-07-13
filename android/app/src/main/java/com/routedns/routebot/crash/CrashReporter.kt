package com.routedns.routebot.crash

import android.content.Context
import com.routedns.routebot.BuildConfig
import com.routedns.routebot.common.RouteBotLog
import com.routedns.routebot.domain.model.CrashReport
import com.routedns.routebot.domain.repository.AgentApiRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Crash reporting (PRD §12). Writing the crash to disk synchronously in the
 * uncaught-exception handler is more reliable than attempting a network call
 * there directly — the process can be killed by the OS at any moment after an
 * uncaught exception, before an async request would complete. The pending
 * report is instead uploaded on the next app start.
 */
@Singleton
class CrashReporter @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) {
    private val pendingFile = File(context.filesDir, "pending_crash.json")

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                RouteBotLog.e("uncaught_exception", mapOf("thread" to thread.name), throwable)
                pendingFile.writeText(
                    """{"message":${quote(throwable.message ?: throwable.toString())},"stack_trace":${quote(throwable.stackTraceToString())}}"""
                )
            } catch (_: Exception) {
                // Best effort only — never throw from the crash handler itself.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Call on app startup: uploads and clears any crash captured on a previous run. */
    fun reportPendingCrashIfAny(agentApi: AgentApiRepository) {
        if (!pendingFile.exists()) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val json = pendingFile.readText()
                val message = extractField(json, "message")
                val stackTrace = extractField(json, "stack_trace")
                agentApi.reportCrash(CrashReport(message, stackTrace, BuildConfig.VERSION_NAME))
                pendingFile.delete()
            } catch (e: Exception) {
                RouteBotLog.w("crash_report_upload_failed", throwable = e)
            }
        }
    }

    private fun quote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun extractField(json: String, field: String): String {
        val regex = "\"$field\":\"(.*?)\"(?:,|})".toRegex(RegexOption.DOT_MATCHES_ALL)
        val raw = regex.find(json)?.groupValues?.get(1) ?: ""
        return raw.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
    }
}

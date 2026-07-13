package com.routedns.routebot.common

import android.util.Log

object RouteBotLog {
    private const val TAG = "RouteBot"

    fun d(event: String, fields: Map<String, Any?> = emptyMap()) {
        Log.d(TAG, format(event, fields))
    }

    fun i(event: String, fields: Map<String, Any?> = emptyMap()) {
        Log.i(TAG, format(event, fields))
    }

    fun w(event: String, fields: Map<String, Any?> = emptyMap(), throwable: Throwable? = null) {
        Log.w(TAG, format(event, fields), throwable)
    }

    fun e(event: String, fields: Map<String, Any?> = emptyMap(), throwable: Throwable? = null) {
        Log.e(TAG, format(event, fields), throwable)
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
}

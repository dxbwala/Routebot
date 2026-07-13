package com.routedns.routebot.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentServiceController @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun startAgent() {
        val intent = Intent(context, AgentForegroundService::class.java)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopAgent() {
        context.stopService(Intent(context, AgentForegroundService::class.java))
    }

    fun restartAgent() {
        stopAgent()
        startAgent()
    }
}

package com.routedns.routebot.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.routedns.routebot.BuildConfig
import com.routedns.routebot.MainActivity
import com.routedns.routebot.R
import com.routedns.routebot.command.CommandExecutor
import com.routedns.routebot.common.Constants
import com.routedns.routebot.common.RouteBotLog
import com.routedns.routebot.core.monitor.DeviceMonitor
import com.routedns.routebot.data.remote.OkHttpClientFactory
import com.routedns.routebot.data.remote.websocket.AgentWebSocketClient
import com.routedns.routebot.data.repository.AgentApiRepositoryImpl
import com.routedns.routebot.domain.model.DeviceHeartbeat
import com.routedns.routebot.domain.repository.AgentApiRepository
import com.routedns.routebot.domain.repository.SecureStorageRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

@AndroidEntryPoint
class AgentForegroundService : Service() {

    @Inject lateinit var secureStorage: SecureStorageRepository
    @Inject lateinit var agentApi: AgentApiRepository
    @Inject lateinit var agentApiImpl: AgentApiRepositoryImpl
    @Inject lateinit var webSocketClient: AgentWebSocketClient
    @Inject lateinit var okHttpFactory: OkHttpClientFactory
    @Inject lateinit var deviceMonitor: DeviceMonitor
    @Inject lateinit var commandExecutor: CommandExecutor

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundWithType(buildNotification("Starting…"))
        observeCommands()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch { startAgent() }
        return START_STICKY
    }

    override fun onDestroy() {
        heartbeatJob?.cancel()
        webSocketClient.disconnect()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun startAgent() {
        val serverUrl = secureStorage.getServerUrl()
        val apiKey = secureStorage.getApiKey()
        if (serverUrl.isNullOrBlank() || apiKey.isNullOrBlank()) {
            RouteBotLog.w("agent_not_configured")
            stopSelf()
            return
        }

        val client = okHttpFactory.create(serverUrl)
        webSocketClient.connect(serverUrl, apiKey, client)

        webSocketClient.connected.onEach {
            agentApiImpl.flushQueue()
        }.launchIn(scope)

        heartbeatJob?.cancel()
        heartbeatJob = scope.launch { heartbeatLoop() }
    }

    private suspend fun heartbeatLoop() {
        while (scope.isActive) {
            val snapshot = deviceMonitor.collect(BuildConfig.VERSION_NAME)
            val heartbeat = DeviceHeartbeat(
                batteryLevel = snapshot.batteryLevel.takeIf { it >= 0 },
                isCharging = snapshot.isCharging,
                storageFreeMb = snapshot.storageFreeMb,
                ramFreeMb = snapshot.ramFreeMb,
                networkType = snapshot.networkType,
                wifiSsid = snapshot.wifiSsid,
                signalStrength = snapshot.signalStrength,
                reportedAt = Instant.now().toString()
            )
            agentApi.sendHeartbeat(heartbeat)
            webSocketClient.sendHeartbeat()
            delay(Constants.HEARTBEAT_INTERVAL_MS)
        }
    }

    private fun observeCommands() {
        webSocketClient.commands
            .onEach { commandExecutor.handle(it) }
            .launchIn(scope)
    }

    private fun buildNotification(text: String): Notification {
        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, Constants.AGENT_CHANNEL_ID)
            .setContentTitle(getString(R.string.agent_notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundWithType(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                Constants.AGENT_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(Constants.AGENT_NOTIFICATION_ID, notification)
        }
    }
}

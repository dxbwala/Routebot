package com.routedns.routebot.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.routedns.routebot.BuildConfig
import com.routedns.routebot.common.Constants
import com.routedns.routebot.common.RouteBotLog
import com.routedns.routebot.core.monitor.DeviceMonitor
import com.routedns.routebot.domain.model.DeviceHeartbeat
import com.routedns.routebot.domain.repository.AgentApiRepository
import com.routedns.routebot.domain.repository.SecureStorageRepository
import com.routedns.routebot.monitor.CallStateMonitor
import com.routedns.routebot.service.AgentServiceController
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.serialization.encodeToString
import java.time.Instant
import java.util.concurrent.TimeUnit

@HiltWorker
class HealthSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val secureStorage: SecureStorageRepository,
    private val agentApi: AgentApiRepository,
    private val deviceMonitor: DeviceMonitor,
    private val serviceController: AgentServiceController,
    private val callStateMonitor: CallStateMonitor,
    private val simPhoneDiscovery: com.routedns.routebot.ussd.SimPhoneDiscovery,
    private val json: kotlinx.serialization.json.Json
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!secureStorage.isRegistered()) return Result.success()
        RouteBotLog.i("health_sync_start")
        callStateMonitor.start()
        serviceController.startAgent()
        runCatching { simPhoneDiscovery.ensurePhoneNumbers() }
        val snapshot = deviceMonitor.collect(BuildConfig.VERSION_NAME)
        agentApi.sendHeartbeat(
            DeviceHeartbeat(
                batteryLevel = snapshot.batteryLevel.takeIf { it >= 0 },
                isCharging = snapshot.isCharging,
                storageFreeMb = snapshot.storageFreeMb,
                ramFreeMb = snapshot.ramFreeMb,
                cpuUsage = snapshot.cpuUsage,
                networkType = snapshot.networkType,
                wifiSsid = snapshot.wifiSsid,
                signalStrength = snapshot.signalStrength,
                simInfo = json.parseToJsonElement(json.encodeToString(snapshot.simInfo)),
                reportedAt = Instant.now().toString()
            )
        )
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<HealthSyncWorker>(
                Constants.HEALTH_SYNC_INTERVAL_MINUTES,
                TimeUnit.MINUTES
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                Constants.HEALTH_SYNC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}

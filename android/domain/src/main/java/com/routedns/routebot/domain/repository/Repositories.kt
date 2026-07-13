package com.routedns.routebot.domain.repository

import com.routedns.routebot.common.Result
import com.routedns.routebot.domain.model.AgentConfig
import com.routedns.routebot.domain.model.CallEvent
import com.routedns.routebot.domain.model.CommandAckRequest
import com.routedns.routebot.domain.model.Device
import com.routedns.routebot.domain.model.DeviceHeartbeat
import com.routedns.routebot.domain.model.DeviceRegistrationRequest
import com.routedns.routebot.domain.model.DeviceRegistrationResponse
import com.routedns.routebot.domain.model.LoginRequest
import com.routedns.routebot.domain.model.NotificationEvent
import com.routedns.routebot.domain.model.OtpEvent
import com.routedns.routebot.domain.model.QueuedEvent
import com.routedns.routebot.domain.model.SmsMessage
import com.routedns.routebot.domain.model.TokenPair
import kotlinx.coroutines.flow.Flow
import java.io.File

interface SecureStorageRepository {
    suspend fun saveApiKey(apiKey: String)
    suspend fun getApiKey(): String?
    suspend fun saveServerUrl(url: String)
    suspend fun getServerUrl(): String?
    suspend fun saveDeviceId(deviceId: String)
    suspend fun getDeviceId(): String?
    suspend fun saveDeviceUuid(uuid: String)
    suspend fun getDeviceUuid(): String?
    suspend fun saveDeviceName(name: String)
    suspend fun getDeviceName(): String?
    suspend fun saveJwtAccess(token: String)
    suspend fun getJwtAccess(): String?
    suspend fun saveOtpPatterns(patterns: List<String>)
    suspend fun getOtpPatterns(): List<String>
    suspend fun saveNotificationPackages(packages: List<String>)
    suspend fun getNotificationPackages(): List<String>
    suspend fun saveCertificatePins(pins: List<String>)
    suspend fun getCertificatePins(): List<String>
    suspend fun isRegistered(): Boolean
    suspend fun clearAll()
}

interface AuthRepository {
    suspend fun login(request: LoginRequest): Result<TokenPair>
    suspend fun registerDevice(
        accessToken: String,
        request: DeviceRegistrationRequest
    ): Result<DeviceRegistrationResponse>
    suspend fun registerWithApiKey(apiKey: String, serverUrl: String): Result<Unit>
    suspend fun claimEnrollment(
        serverUrl: String,
        request: com.routedns.routebot.domain.model.EnrollmentClaimRequest
    ): Result<DeviceRegistrationResponse>
}

interface AgentApiRepository {
    suspend fun sendHeartbeat(heartbeat: DeviceHeartbeat): Result<Unit>
    suspend fun sendSms(message: SmsMessage): Result<Unit>
    suspend fun sendOtp(event: OtpEvent): Result<Unit>
    suspend fun sendNotification(event: NotificationEvent): Result<Unit>
    suspend fun sendCall(event: CallEvent): Result<Unit>
    suspend fun ackCommand(commandId: String, ack: CommandAckRequest): Result<Unit>
    suspend fun uploadMedia(
        mediaType: String,
        commandId: String?,
        file: File,
        contentType: String
    ): Result<Unit>
    suspend fun flushQueue()
}

interface OfflineQueueRepository {
    suspend fun enqueue(type: String, payload: String)
    suspend fun peekBatch(limit: Int = 50): List<QueuedEvent>
    suspend fun markSent(id: Long)
    suspend fun markFailed(id: Long)
    fun observePendingCount(): Flow<Int>
}

interface ConfigRepository {
    fun observeConfig(): Flow<AgentConfig>
    suspend fun getConfig(): AgentConfig
    suspend fun updateConfig(config: AgentConfig)
}

interface DeviceRepository {
    suspend fun getLocalDevice(): Device?
    suspend fun saveLocalDevice(device: Device)
}

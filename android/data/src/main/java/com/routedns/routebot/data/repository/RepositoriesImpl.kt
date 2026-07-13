package com.routedns.routebot.data.repository

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.routedns.routebot.common.Result
import com.routedns.routebot.common.RouteBotLog
import com.routedns.routebot.data.local.dao.QueuedEventDao
import com.routedns.routebot.data.local.entity.QueuedEventEntity
import com.routedns.routebot.data.remote.OkHttpClientFactory
import com.routedns.routebot.data.remote.api.RouteBotApi
import com.routedns.routebot.domain.model.AgentConfig
import com.routedns.routebot.domain.model.CallEvent
import com.routedns.routebot.domain.model.CommandAckRequest
import com.routedns.routebot.domain.model.Device
import com.routedns.routebot.domain.model.DeviceHeartbeat
import com.routedns.routebot.domain.model.DeviceRegistrationRequest
import com.routedns.routebot.domain.model.DeviceRegistrationResponse
import com.routedns.routebot.domain.model.EventTypes
import com.routedns.routebot.domain.model.LoginRequest
import com.routedns.routebot.domain.model.NotificationEvent
import com.routedns.routebot.domain.model.OtpEvent
import com.routedns.routebot.domain.model.QueuedEvent
import com.routedns.routebot.domain.model.SmsMessage
import com.routedns.routebot.domain.model.TokenPair
import com.routedns.routebot.domain.repository.AgentApiRepository
import com.routedns.routebot.domain.repository.AuthRepository
import com.routedns.routebot.domain.repository.ConfigRepository
import com.routedns.routebot.domain.repository.DeviceRepository
import com.routedns.routebot.domain.repository.OfflineQueueRepository
import com.routedns.routebot.domain.repository.SecureStorageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class JsonProvider @Inject constructor() {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }
}

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val secureStorage: SecureStorageRepository,
    private val okHttpFactory: OkHttpClientFactory,
    private val jsonProvider: JsonProvider
) : AuthRepository {

    private suspend fun api(baseUrl: String): RouteBotApi = createApi(baseUrl)

    override suspend fun login(request: LoginRequest): Result<TokenPair> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = secureStorage.getServerUrl() ?: return@withContext Result.Error("Server URL not configured")
            val response = api(baseUrl).login(request)
            if (response.success && response.data != null) {
                Result.Success(response.data.tokens)
            } else {
                Result.Error(response.error?.message ?: "Login failed")
            }
        } catch (e: Exception) {
            Result.Error(e.message ?: "Login failed", e)
        }
    }

    override suspend fun registerDevice(
        accessToken: String,
        request: DeviceRegistrationRequest
    ): Result<DeviceRegistrationResponse> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = secureStorage.getServerUrl() ?: return@withContext Result.Error("Server URL not configured")
            val response = api(baseUrl).registerDevice("Bearer $accessToken", request)
            if (response.success && response.data != null) {
                Result.Success(
                    DeviceRegistrationResponse(response.data.device, response.data.apiKey)
                )
            } else {
                Result.Error(response.error?.message ?: "Device registration failed")
            }
        } catch (e: Exception) {
            Result.Error(e.message ?: "Device registration failed", e)
        }
    }

    override suspend fun registerWithApiKey(apiKey: String, serverUrl: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            secureStorage.saveApiKey(apiKey)
            secureStorage.saveServerUrl(serverUrl.trimEnd('/'))
            Result.Success(Unit)
        }

    override suspend fun claimEnrollment(
        serverUrl: String,
        request: com.routedns.routebot.domain.model.EnrollmentClaimRequest
    ): Result<DeviceRegistrationResponse> = withContext(Dispatchers.IO) {
        try {
            val base = serverUrl.trim().trimEnd('/')
            secureStorage.saveServerUrl(base)
            val response = api(base).claimEnrollment(request)
            if (response.success && response.data != null) {
                Result.Success(
                    DeviceRegistrationResponse(response.data.device, response.data.apiKey)
                )
            } else {
                Result.Error(response.error?.message ?: "Enrollment claim failed")
            }
        } catch (e: Exception) {
            Result.Error(e.message ?: "Enrollment claim failed", e)
        }
    }

    private fun createApi(baseUrl: String): RouteBotApi {
        val contentType = "application/json".toMediaType()
        val client = okHttpFactory.create(baseUrl)
        return Retrofit.Builder()
            .baseUrl(baseUrl.trimEnd('/') + "/")
            .client(client)
            .addConverterFactory(jsonProvider.json.asConverterFactory(contentType))
            .build()
            .create(RouteBotApi::class.java)
    }
}

@Singleton
class AgentApiRepositoryImpl @Inject constructor(
    private val secureStorage: SecureStorageRepository,
    private val okHttpFactory: OkHttpClientFactory,
    private val jsonProvider: JsonProvider,
    private val offlineQueue: OfflineQueueRepository
) : AgentApiRepository {

    private suspend fun api(): RouteBotApi? {
        val baseUrl = secureStorage.getServerUrl() ?: return null
        val contentType = "application/json".toMediaType()
        val client = okHttpFactory.create(baseUrl)
        return Retrofit.Builder()
            .baseUrl(baseUrl.trimEnd('/') + "/")
            .client(client)
            .addConverterFactory(jsonProvider.json.asConverterFactory(contentType))
            .build()
            .create(RouteBotApi::class.java)
    }

    private suspend fun apiKey(): String? = secureStorage.getApiKey()

    // Guards flushQueue() against concurrent invocations (WS reconnect + SYNC command can
    // both trigger a flush at the same time), which otherwise peek and dispatch the same
    // queued SMS/OTP rows twice before either deletes them.
    private val flushMutex = Mutex()

    override suspend fun sendHeartbeat(heartbeat: DeviceHeartbeat): Result<Unit> =
        postOrQueue(EventTypes.HEARTBEAT, heartbeat) { key, api ->
            api.heartbeat(key, heartbeat)
        }

    override suspend fun sendSms(message: SmsMessage): Result<Unit> =
        postOrQueue(EventTypes.SMS, message) { key, api -> api.ingestSms(key, message) }

    override suspend fun sendOtp(event: OtpEvent): Result<Unit> =
        postOrQueue(EventTypes.OTP, event) { key, api -> api.ingestOtp(key, event) }

    override suspend fun sendNotification(event: NotificationEvent): Result<Unit> =
        postOrQueue(EventTypes.NOTIFICATION, event) { key, api -> api.ingestNotification(key, event) }

    override suspend fun sendCall(event: CallEvent): Result<Unit> =
        postOrQueue(EventTypes.CALL, event) { key, api -> api.ingestCall(key, event) }

    override suspend fun ackCommand(commandId: String, ack: CommandAckRequest): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val key = apiKey() ?: return@withContext Result.Error("API key missing")
                val api = api() ?: return@withContext Result.Error("API not configured")
                val response = api.ackCommand(key, commandId, ack)
                if (response.success) Result.Success(Unit)
                else Result.Error(response.error?.message ?: "Ack failed")
            } catch (e: Exception) {
                Result.Error(e.message ?: "Ack failed", e)
            }
        }

    override suspend fun uploadMedia(
        mediaType: String,
        commandId: String?,
        file: File,
        contentType: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val key = apiKey() ?: return@withContext Result.Error("API key missing")
            val api = api() ?: return@withContext Result.Error("API not configured")
            val body = file.asRequestBody(contentType.toMediaType())
            val part = MultipartBody.Part.createFormData("file", file.name, body)
            val typeBody = mediaType.toRequestBody("text/plain".toMediaType())
            val cmdBody = commandId?.toRequestBody("text/plain".toMediaType())
            val response = api.uploadMedia(key, typeBody, cmdBody, part)
            if (response.success) Result.Success(Unit)
            else Result.Error(response.error?.message ?: "Upload failed")
        } catch (e: Exception) {
            Result.Error(e.message ?: "Upload failed", e)
        }
    }

    private suspend inline fun <reified T> postOrQueue(
        type: String,
        payload: T,
        crossinline call: suspend (String, RouteBotApi) -> Any
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val key = apiKey()
        val api = api()
        if (key == null || api == null) {
            offlineQueue.enqueue(type, jsonProvider.json.encodeToString(payload))
            return@withContext Result.Error("Offline — queued")
        }
        try {
            val response = call(key, api)
            if (response is com.routedns.routebot.data.remote.dto.ApiResponse<*>) {
                if (response.success) Result.Success(Unit)
                else Result.Error(response.error?.message ?: "Request failed")
            } else {
                Result.Success(Unit)
            }
        } catch (e: Exception) {
            offlineQueue.enqueue(type, jsonProvider.json.encodeToString(payload))
            RouteBotLog.w("api_enqueue_offline", mapOf("type" to type), e)
            Result.Error("Offline — queued", e)
        }
    }

    override suspend fun flushQueue() = withContext(Dispatchers.IO) {
        flushMutex.withLock {
            val key = apiKey() ?: return@withLock
            val api = api() ?: return@withLock
            val batch = offlineQueue.peekBatch()
            for (event in batch) {
                val ok = dispatchQueued(key, api, event)
                if (ok) offlineQueue.markSent(event.id) else offlineQueue.markFailed(event.id)
            }
        }
    }

    private suspend fun dispatchQueued(key: String, api: RouteBotApi, event: QueuedEvent): Boolean {
        return try {
            when (event.type) {
                EventTypes.HEARTBEAT -> {
                    val hb = jsonProvider.json.decodeFromString<DeviceHeartbeat>(event.payload)
                    api.heartbeat(key, hb).success
                }
                EventTypes.SMS -> {
                    val msg = jsonProvider.json.decodeFromString<SmsMessage>(event.payload)
                    api.ingestSms(key, msg).success
                }
                EventTypes.OTP -> {
                    val otp = jsonProvider.json.decodeFromString<OtpEvent>(event.payload)
                    api.ingestOtp(key, otp).success
                }
                EventTypes.NOTIFICATION -> {
                    val n = jsonProvider.json.decodeFromString<NotificationEvent>(event.payload)
                    api.ingestNotification(key, n).success
                }
                EventTypes.CALL -> {
                    val c = jsonProvider.json.decodeFromString<CallEvent>(event.payload)
                    api.ingestCall(key, c).success
                }
                else -> true
            }
        } catch (e: Exception) {
            RouteBotLog.w("flush_failed", mapOf("type" to event.type), e)
            false
        }
    }
}

@Singleton
class OfflineQueueRepositoryImpl @Inject constructor(
    private val dao: QueuedEventDao
) : OfflineQueueRepository {
    override suspend fun enqueue(type: String, payload: String) {
        dao.insert(QueuedEventEntity(type = type, payload = payload, createdAt = System.currentTimeMillis()))
    }

    override suspend fun peekBatch(limit: Int): List<QueuedEvent> =
        dao.peek(limit).map { QueuedEvent(it.id, it.type, it.payload, it.createdAt, it.retryCount) }

    override suspend fun markSent(id: Long) = dao.delete(id)
    override suspend fun markFailed(id: Long) = dao.incrementRetry(id)
    override fun observePendingCount(): Flow<Int> = dao.observeCount()
}

@Singleton
class ConfigRepositoryImpl @Inject constructor(
    private val secureStorage: SecureStorageRepository
) : ConfigRepository {
    private val configFlow = MutableStateFlow(AgentConfig())

    override fun observeConfig(): Flow<AgentConfig> = configFlow

    override suspend fun getConfig(): AgentConfig = withContext(Dispatchers.IO) {
        AgentConfig(
            serverUrl = secureStorage.getServerUrl().orEmpty(),
            deviceName = secureStorage.getDeviceName().orEmpty(),
            otpPatterns = secureStorage.getOtpPatterns(),
            notificationPackages = secureStorage.getNotificationPackages(),
            certificatePins = secureStorage.getCertificatePins()
        ).also { configFlow.value = it }
    }

    override suspend fun updateConfig(config: AgentConfig) = withContext(Dispatchers.IO) {
        secureStorage.saveServerUrl(config.serverUrl)
        secureStorage.saveDeviceName(config.deviceName)
        secureStorage.saveOtpPatterns(config.otpPatterns)
        secureStorage.saveNotificationPackages(config.notificationPackages)
        secureStorage.saveCertificatePins(config.certificatePins)
        configFlow.value = config
    }
}

@Singleton
class DeviceRepositoryImpl @Inject constructor(
    private val secureStorage: SecureStorageRepository
) : DeviceRepository {
    override suspend fun getLocalDevice(): Device? {
        val id = secureStorage.getDeviceId() ?: return null
        return Device(
            id = id,
            deviceUuid = secureStorage.getDeviceUuid().orEmpty(),
            name = secureStorage.getDeviceName().orEmpty()
        )
    }

    override suspend fun saveLocalDevice(device: Device) {
        secureStorage.saveDeviceId(device.id)
        secureStorage.saveDeviceUuid(device.deviceUuid)
        secureStorage.saveDeviceName(device.name)
    }
}

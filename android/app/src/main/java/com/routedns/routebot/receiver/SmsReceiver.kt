package com.routedns.routebot.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.routedns.routebot.common.RouteBotLog
import com.routedns.routebot.domain.model.SmsDirection
import com.routedns.routebot.domain.model.SmsMessage
import com.routedns.routebot.domain.repository.AgentApiRepository
import dagger.hilt.android.EntryPointAccessors

/**
 * Entry point for [SmsReceiver] because broadcast receivers cannot use field injection
 * reliably before [onReceive] on all API levels.
 */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface SmsReceiverEntryPoint {
    fun agentApi(): AgentApiRepository
    fun extractOtp(): com.routedns.routebot.domain.usecase.ExtractOtpUseCase
    fun serviceController(): com.routedns.routebot.service.AgentServiceController
}

@android.annotation.SuppressLint("UnsafeProtectedBroadcastReceiver")
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != android.provider.Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val pending = goAsync()
        val entry = EntryPointAccessors.fromApplication(context, SmsReceiverEntryPoint::class.java)
        val agentApi = entry.agentApi()
        val extractOtp = entry.extractOtp()
        val serviceController = entry.serviceController()

        Thread {
            try {
                serviceController.startAgent()
                val messages = android.provider.Telephony.Sms.Intents.getMessagesFromIntent(intent)
                for (sms in messages) {
                    val address = sms.displayOriginatingAddress.orEmpty()
                    val body = sms.messageBody.orEmpty()
                    val slot = intent.extras?.getInt("slot", 0) ?: 0
                    val msg = SmsMessage(
                        direction = SmsDirection.INBOUND,
                        address = address,
                        body = body,
                        simSlot = slot,
                        status = "received"
                    )
                    kotlinx.coroutines.runBlocking { agentApi.sendSms(msg) }
                    kotlinx.coroutines.runBlocking {
                        extractOtp(address, body, com.routedns.routebot.domain.model.OtpSource.SMS)?.let {
                            agentApi.sendOtp(it)
                        }
                    }
                }
            } catch (e: Exception) {
                RouteBotLog.e("sms_receive_failed", throwable = e)
            } finally {
                pending.finish()
            }
        }.start()
    }
}

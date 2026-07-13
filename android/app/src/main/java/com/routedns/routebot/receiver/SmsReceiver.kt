package com.routedns.routebot.receiver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import com.routedns.routebot.common.RouteBotLog
import com.routedns.routebot.common.SimSlots
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
                val apiSlot = resolveApiSimSlot(context, intent)
                for (sms in messages) {
                    val address = sms.displayOriginatingAddress.orEmpty()
                    val body = sms.messageBody.orEmpty()
                    val msg = SmsMessage(
                        direction = SmsDirection.INBOUND,
                        address = address,
                        body = body,
                        simSlot = apiSlot,
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

    /**
     * Normalize OEM extras to API `sim_slot` (1 = SIM 1, 2 = SIM 2).
     * Prefer subscription id → physical tray; fall back to 0-based OEM `slot`.
     */
    private fun resolveApiSimSlot(context: Context, intent: Intent): Int {
        val extras = intent.extras ?: return SimSlots.DEFAULT
        val subId = sequenceOf("subscription", "subscription_id", "sub_id")
            .map { extras.getInt(it, SubscriptionManager.INVALID_SUBSCRIPTION_ID) }
            .firstOrNull { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID && it > 0 }
        if (subId != null &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                val sm = context.getSystemService(SubscriptionManager::class.java)
                val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    sm?.getActiveSubscriptionInfo(subId)
                } else {
                    null
                }
                val androidSlot = info?.simSlotIndex?.takeIf { it >= 0 }
                if (androidSlot != null) return SimSlots.toApiSimSlot(androidSlot)
            } catch (_: Exception) {
                // fall through
            }
        }
        val raw = sequenceOf("slot", "simSlot", "sim_slot", "phone")
            .map { extras.getInt(it, Int.MIN_VALUE) }
            .firstOrNull { it != Int.MIN_VALUE }
            ?: return SimSlots.DEFAULT
        // OEM extras are usually Android 0-based; values already 1|2 pass through.
        return if (raw <= 0) SimSlots.toApiSimSlot(raw.coerceAtLeast(0)) else raw.coerceIn(SimSlots.SIM_1, SimSlots.SIM_2)
    }
}

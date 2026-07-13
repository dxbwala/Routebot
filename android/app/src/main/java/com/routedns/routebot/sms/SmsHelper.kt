package com.routedns.routebot.sms

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import com.routedns.routebot.common.RouteBotLog
import com.routedns.routebot.domain.model.SmsDirection
import com.routedns.routebot.domain.model.SmsMessage
import com.routedns.routebot.domain.repository.AgentApiRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

private const val ACTION_SMS_SENT = "com.routedns.routebot.SMS_SENT"
private const val ACTION_SMS_DELIVERED = "com.routedns.routebot.SMS_DELIVERED"
private const val EXTRA_CORRELATION_ID = "correlation_id"

/**
 * Sends SMS and reports real delivery status (PRD §4 "Delivery report where supported").
 *
 * Flow: register dynamic receivers for the OS's sent/delivered `PendingIntent` callbacks,
 * send via [SmsManager], await the "sent" result synchronously (near-instant), then keep
 * listening for the carrier's "delivered" confirmation in the background (which can take
 * seconds to minutes, or never arrive on carriers that don't support delivery reports —
 * that absence is itself a real-world limitation, not a bug).
 */
@Singleton
class SmsHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val agentApi: AgentApiRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun sendSms(address: String, body: String, simSlot: Int = 0): Result<SmsMessage> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return Result.failure(SecurityException("SEND_SMS not granted"))
        }
        return try {
            val manager = resolveSmsManager(simSlot)
            val parts = manager.divideMessage(body)
            val correlationId = UUID.randomUUID().toString()
            val partCount = parts.size.coerceAtLeast(1)

            val sentResult = SentResultTracker(partCount)
            val sentReceiver = registerResultReceiver(ACTION_SMS_SENT + correlationId) { resultCode ->
                sentResult.onResult(resultCode)
            }
            val deliveredTracker = DeliveredResultTracker(partCount)
            val deliveredReceiver = registerResultReceiver(ACTION_SMS_DELIVERED + correlationId) { resultCode ->
                deliveredTracker.onResult(resultCode)
            }

            val sentIntents = buildPendingIntents(ACTION_SMS_SENT + correlationId, partCount)
            val deliveredIntents = buildPendingIntents(ACTION_SMS_DELIVERED + correlationId, partCount)

            if (parts.size > 1) {
                manager.sendMultipartTextMessage(address, null, parts, sentIntents, deliveredIntents)
            } else {
                manager.sendTextMessage(address, null, body, sentIntents[0], deliveredIntents[0])
            }

            // The OS "sent" broadcast (radio accepted the message) fires near-instantly.
            val sentOk = withTimeoutOrNull(10_000) { sentResult.await() } ?: false
            runCatching { context.unregisterReceiver(sentReceiver) }

            val status = if (sentOk) "sent" else "failed"
            val message = SmsMessage(
                direction = SmsDirection.OUTBOUND,
                address = address,
                body = body,
                simSlot = simSlot,
                status = status
            )

            // Delivery confirmation (carrier ACK) can take much longer, or never arrive on
            // carriers/devices that don't support delivery reports. Report it asynchronously
            // once we know the server-assigned id, with a bounded wait so the receiver is
            // always eventually unregistered.
            scope.launch {
                val created = agentApi.sendSms(message).getOrNull()
                val smsId = created?.id
                val delivered = withTimeoutOrNull(120_000) { deliveredTracker.await() }
                runCatching { context.unregisterReceiver(deliveredReceiver) }
                if (smsId != null && delivered != null) {
                    agentApi.updateSmsStatus(
                        smsId,
                        if (delivered) "delivered" else "delivery_failed",
                        Instant.now().toString()
                    )
                }
            }

            Result.success(message)
        } catch (e: Exception) {
            RouteBotLog.e("sms_send_failed", throwable = e)
            Result.failure(e)
        }
    }

    private fun buildPendingIntents(action: String, count: Int): ArrayList<PendingIntent> {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return ArrayList((0 until count).map {
            PendingIntent.getBroadcast(context, it, Intent(action), flags)
        })
    }

    private fun registerResultReceiver(action: String, onResult: (Int) -> Unit): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                onResult(resultCode)
            }
        }
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        return receiver
    }

    @Suppress("DEPRECATION")
    private fun resolveSmsManager(simSlot: Int): SmsManager {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            val sm = context.getSystemService(SubscriptionManager::class.java)
            val subs = sm?.activeSubscriptionInfoList.orEmpty()
            val subId = subs.getOrNull(simSlot)?.subscriptionId
            if (subId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return context.getSystemService(SmsManager::class.java)
                    .createForSubscriptionId(subId)
            }
            if (subId != null) {
                return SmsManager.getSmsManagerForSubscriptionId(subId)
            }
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
    }

    /** Aggregates per-part "sent" results: overall success only if every part succeeded. */
    private class SentResultTracker(private val expected: Int) {
        private val remaining = AtomicInteger(expected)
        private var allOk = true
        private val deferred = CompletableDeferred<Boolean>()

        fun onResult(resultCode: Int) {
            if (resultCode != android.app.Activity.RESULT_OK) allOk = false
            if (remaining.decrementAndGet() <= 0) deferred.complete(allOk)
        }

        suspend fun await(): Boolean = deferred.await()
    }

    /** Aggregates per-part delivery results: delivered only if every part was delivered. */
    private class DeliveredResultTracker(private val expected: Int) {
        private val remaining = AtomicInteger(expected)
        private var allDelivered = true
        private val deferred = CompletableDeferred<Boolean>()

        fun onResult(resultCode: Int) {
            if (resultCode != android.app.Activity.RESULT_OK) allDelivered = false
            if (remaining.decrementAndGet() <= 0) deferred.complete(allDelivered)
        }

        suspend fun await(): Boolean = deferred.await()
    }
}

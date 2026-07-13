package com.routedns.routebot.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import com.routedns.routebot.common.RouteBotLog
import com.routedns.routebot.domain.model.SmsDirection
import com.routedns.routebot.domain.model.SmsMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmsHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun sendSms(address: String, body: String, simSlot: Int = 0): Result<SmsMessage> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return Result.failure(SecurityException("SEND_SMS not granted"))
        }
        return try {
            val manager = resolveSmsManager(simSlot)
            val parts = manager.divideMessage(body)
            val sentIntent = null
            if (parts.size > 1) {
                manager.sendMultipartTextMessage(address, null, parts, null, null)
            } else {
                manager.sendTextMessage(address, null, body, sentIntent, null)
            }
            Result.success(
                SmsMessage(
                    direction = SmsDirection.OUTBOUND,
                    address = address,
                    body = body,
                    simSlot = simSlot,
                    status = "sent"
                )
            )
        } catch (e: Exception) {
            RouteBotLog.e("sms_send_failed", throwable = e)
            Result.failure(e)
        }
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
}

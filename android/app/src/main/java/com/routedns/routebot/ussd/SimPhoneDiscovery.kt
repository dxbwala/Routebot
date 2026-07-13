package com.routedns.routebot.ussd

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.routedns.routebot.common.Constants
import com.routedns.routebot.common.RouteBotLog
import com.routedns.routebot.common.SimSlots
import com.routedns.routebot.core.monitor.SimPhoneCache
import com.routedns.routebot.domain.repository.SecureStorageRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves SIM MSISDNs for heartbeat `sim_info.phoneNumber`.
 *
 * Order:
 * 1. Cached values (prefs + [SimPhoneCache])
 * 2. Telephony `SubscriptionInfo.number` / `line1Number` (via [DeviceMonitor] on collect)
 * 3. One-shot USSD [Constants.USSD_OWN_NUMBER_CODE] (`*2#`) per subscription when still missing
 *
 * USSD is rate-limited per subscription ([Constants.SIM_PHONE_USSD_COOLDOWN_MS]) so heartbeats
 * never dial repeatedly.
 */
@Singleton
class SimPhoneDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telephonyManager: TelephonyManager,
    private val ussdHelper: UssdHelper,
    private val secureStorage: SecureStorageRepository,
    private val simPhoneCache: SimPhoneCache
) {
    private var loadedFromDisk = false

    suspend fun ensurePhoneNumbers() {
        loadCacheFromDiskIfNeeded()
        seedFromTelephony()

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val sm = context.getSystemService(SubscriptionManager::class.java) ?: return
        val subs = sm.activeSubscriptionInfoList.orEmpty()
        if (subs.isEmpty()) return

        val attempts = secureStorage.getSimPhoneUssdAttemptedAt().toMutableMap()
        val now = System.currentTimeMillis()
        var changed = false

        for (info in subs) {
            val subId = info.subscriptionId
            if (!simPhoneCache.get(subId).isNullOrBlank()) continue

            val lastAttempt = attempts[subId] ?: 0L
            if (now - lastAttempt < Constants.SIM_PHONE_USSD_COOLDOWN_MS) continue

            val androidSlot = info.simSlotIndex.takeIf { it >= 0 } ?: continue
            val apiSlot = SimSlots.toApiSimSlot(androidSlot)
            RouteBotLog.i(
                "sim_phone_ussd_discover",
                mapOf("sim_slot" to apiSlot, "subscription_id" to subId)
            )
            attempts[subId] = now
            secureStorage.saveSimPhoneUssdAttemptedAt(attempts)

            val result = ussdHelper.sendUssd(
                code = Constants.USSD_OWN_NUMBER_CODE,
                subscriptionId = subId,
                steps = emptyList(),
                simSlot = apiSlot
            )
            if (result.isFailure) {
                RouteBotLog.w(
                    "sim_phone_ussd_failed",
                    mapOf("sub" to subId, "error" to (result.exceptionOrNull()?.message ?: ""))
                )
                continue
            }
            val response = result.getOrNull().orEmpty()
            val phone = extractPhoneNumber(response)
            if (phone.isNullOrBlank()) {
                RouteBotLog.w(
                    "sim_phone_ussd_no_number",
                    mapOf("sub" to subId, "response" to response.take(120))
                )
                continue
            }
            simPhoneCache.put(subId, phone)
            changed = true
            RouteBotLog.i("sim_phone_ussd_ok", mapOf("sub" to subId, "phone" to phone))
        }

        if (changed) {
            persistCache()
        }
    }

    private suspend fun loadCacheFromDiskIfNeeded() {
        if (loadedFromDisk) return
        simPhoneCache.putAll(secureStorage.getSimPhoneNumbers())
        loadedFromDisk = true
    }

    private suspend fun seedFromTelephony() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val sm = context.getSystemService(SubscriptionManager::class.java) ?: return
        var changed = false
        for (info in sm.activeSubscriptionInfoList.orEmpty()) {
            if (!simPhoneCache.get(info.subscriptionId).isNullOrBlank()) continue
            @Suppress("DEPRECATION")
            val fromSub = info.number?.trim().orEmpty()
            val candidate = when {
                looksLikePhone(fromSub) -> fromSub
                else -> readLine1(info.subscriptionId)
            }
            if (looksLikePhone(candidate)) {
                simPhoneCache.put(info.subscriptionId, candidate)
                changed = true
            }
        }
        if (changed) persistCache()
    }

    @Suppress("DEPRECATION")
    private fun readLine1(subscriptionId: Int): String {
        return try {
            val tm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                telephonyManager.createForSubscriptionId(subscriptionId)
            } else {
                telephonyManager
            }
            tm.line1Number?.trim().orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private suspend fun persistCache() {
        secureStorage.saveSimPhoneNumbers(simPhoneCache.snapshot())
    }

    companion object {
        fun extractPhoneNumber(raw: String): String? {
            val text = raw.replace('\u202a', ' ').replace('\u202c', ' ')
            val patterns = listOf(
                Regex("""(?:My\s*Number|Number|MSISDN|Mobile)[:\s]+(\+?\d[\d\s-]{7,18}\d)""", RegexOption.IGNORE_CASE),
                Regex("""(\+?88\d{10})"""),
                Regex("""(\+?\d{10,15})"""),
                Regex("""\b(0\d{9,11})\b""")
            )
            for (pattern in patterns) {
                val match = pattern.find(text) ?: continue
                val group = match.groupValues.lastOrNull()?.filter { it.isDigit() || it == '+' }.orEmpty()
                val digits = group.filter { it.isDigit() }
                if (digits.length in 8..15) {
                    return if (group.startsWith("+")) "+$digits" else group.filter { it.isDigit() || it == '+' }
                }
            }
            return null
        }

        private fun looksLikePhone(raw: String): Boolean {
            val digits = raw.filter { it.isDigit() }
            return digits.length in 8..15
        }
    }
}

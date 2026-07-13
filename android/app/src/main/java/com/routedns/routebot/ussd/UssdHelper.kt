package com.routedns.routebot.ussd

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.routedns.routebot.common.RouteBotLog
import com.routedns.routebot.common.SimSlots
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * USSD helper with OEM-resilient fallbacks and interactive menu replies.
 *
 * Path 1: [TelephonyManager.sendUssdRequest] (API 26+) — single-shot only.
 * Path 2: Dial + Accessibility scrape/type — required for Oppo/ColorOS and multi-step menus.
 *
 * SIM selection: prefer explicit [subscriptionId]; otherwise resolve `sim_slot` 1|2 to that
 * tray's subscription. If both are omitted, the device Dial/default voice SIM is used.
 */
@Singleton
class UssdHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telephonyManager: TelephonyManager
) {
    suspend fun sendUssd(
        code: String,
        subscriptionId: Int? = null,
        steps: List<String> = emptyList(),
        simSlot: Int? = null
    ): Result<String> {
        val normalized = normalizeCode(code)
            ?: return Result.failure(IllegalArgumentException("invalid USSD code"))

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return Result.failure(
                SecurityException("CALL_PHONE permission required for USSD")
            )
        }

        val resolvedSubId = subscriptionId ?: resolveSubscriptionIdForSimSlot(simSlot)

        // Telephony API cannot walk interactive menus — skip it when steps are provided.
        if (steps.isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val apiResult = withTimeoutOrNull(API_TIMEOUT_MS) {
                sendViaTelephonyApi(normalized, resolvedSubId)
            }
            when {
                apiResult == null -> RouteBotLog.w("ussd_api_timeout", mapOf("code" to normalized))
                apiResult.isSuccess -> return apiResult
                else -> RouteBotLog.w(
                    "ussd_api_failed",
                    mapOf("error" to (apiResult.exceptionOrNull()?.message ?: "unknown"))
                )
            }
        }

        return sendViaDialCapture(normalized, resolvedSubId, steps)
    }

    /**
     * Map API `sim_slot` (1 = SIM 1, 2 = SIM 2) to Android subscription id.
     * Returns null when [simSlot] is null so dialer uses the default voice SIM.
     */
    fun resolveSubscriptionIdForSimSlot(simSlot: Int?): Int? {
        if (simSlot == null) return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            RouteBotLog.w("ussd_sim_slot_no_permission", mapOf("sim_slot" to simSlot))
            return null
        }
        val apiSlot = SimSlots.normalizeApiSimSlot(simSlot)
        val androidSlot = SimSlots.toAndroidSlotIndex(apiSlot)
        return try {
            val sm = context.getSystemService(SubscriptionManager::class.java) ?: return null
            val subs = sm.activeSubscriptionInfoList.orEmpty()
            val matched = subs.firstOrNull { it.simSlotIndex == androidSlot }
                ?: subs.sortedBy { it.simSlotIndex.takeIf { idx -> idx >= 0 } ?: Int.MAX_VALUE }
                    .getOrNull(androidSlot)
            matched?.subscriptionId.also {
                if (it == null) {
                    RouteBotLog.w(
                        "ussd_sim_slot_not_found",
                        mapOf("sim_slot" to apiSlot, "active" to subs.size)
                    )
                }
            }
        } catch (e: Exception) {
            RouteBotLog.w("ussd_sim_slot_resolve_failed", mapOf("error" to (e.message ?: "")))
            null
        }
    }

    private suspend fun sendViaTelephonyApi(
        code: String,
        subscriptionId: Int?
    ): Result<String> = suspendCancellableCoroutine { cont ->
        val handler = Handler(Looper.getMainLooper())
        val tm = telephonyFor(subscriptionId)
        try {
            tm.sendUssdRequest(
                code,
                object : TelephonyManager.UssdResponseCallback() {
                    override fun onReceiveUssdResponse(
                        telephonyManager: TelephonyManager,
                        request: String,
                        response: CharSequence
                    ) {
                        if (cont.isActive) cont.resume(Result.success(response.toString()))
                    }

                    override fun onReceiveUssdResponseFailed(
                        telephonyManager: TelephonyManager,
                        request: String,
                        failureCode: Int
                    ) {
                        if (cont.isActive) {
                            cont.resume(
                                Result.failure(IllegalStateException("USSD failed: $failureCode"))
                            )
                        }
                    }
                },
                handler
            )
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(Result.failure(e))
        }
    }

    private suspend fun sendViaDialCapture(
        code: String,
        subscriptionId: Int?,
        steps: List<String>
    ): Result<String> = withContext(Dispatchers.Main) {
        if (!UssdAccessibilityService.isEnabledInSettings(context) &&
            !UssdAccessibilityService.isConnected()
        ) {
            return@withContext Result.failure(
                IllegalStateException(
                    "USSD dial fallback needs Accessibility enabled " +
                        "(Settings → Accessibility → RouteBot USSD Capture)"
                )
            )
        }

        val transcript = mutableListOf<String>()
        try {
            // Interactive menus need the reply field; single-shot can accept any panel.
            val waitForInput = steps.isNotEmpty()
            var capture = UssdAccessibilityService.armCapture(
                resetLast = true,
                requireInput = waitForInput
            )
            placeUssdCall(code, subscriptionId)

            val first = withTimeoutOrNull(DIAL_CAPTURE_TIMEOUT_MS) { capture.await() }
                ?: return@withContext Result.failure(
                    IllegalStateException(
                        "USSD dialed but no dialog text captured " +
                            "(enable Accessibility and keep screen unlocked)"
                    )
                )
            transcript += first
            RouteBotLog.i("ussd_step_prompt", mapOf("n" to 0, "chars" to first.length))

            steps.forEachIndexed { index, step ->
                delay(500)
                val previous = UssdAccessibilityService.normalizePrompt(transcript.last())
                val isLastStep = index == steps.lastIndex
                // After the last reply we want the terminal result (no input required).
                capture = UssdAccessibilityService.armCapture(
                    resetLast = false,
                    requireInput = !isLastStep
                )
                val sent = UssdAccessibilityService.reply(step)
                if (!sent) {
                    return@withContext Result.failure(
                        IllegalStateException(
                            "USSD step ${index + 1} failed: could not type '$step' into dialog"
                        )
                    )
                }
                var fallbackPrompt: String? = null
                val next = withTimeoutOrNull(STEP_CAPTURE_TIMEOUT_MS) {
                    var current = capture
                    while (true) {
                        val candidate = current.await()
                        val norm = UssdAccessibilityService.normalizePrompt(candidate)
                        val pinOnlyEcho = looksLikePinEcho(norm, step)
                        val isStale = norm.equals(previous, ignoreCase = true) || pinOnlyEcho
                        if (isStale || norm.isBlank()) {
                            RouteBotLog.i("ussd_skip_stale_prompt", mapOf("chars" to norm.length))
                            current = UssdAccessibilityService.armCapture(
                                resetLast = false,
                                requireInput = !isLastStep
                            )
                            continue
                        }
                        // On the final PIN submit, ignore interim "Enter PIN" screens and
                        // wait for a result (balance / success / error) when possible.
                        if (isLastStep && looksLikePinPrompt(norm) && !looksLikeUssdResult(norm)) {
                            fallbackPrompt = norm
                            RouteBotLog.i("ussd_wait_final_result", mapOf("chars" to norm.length))
                            current = UssdAccessibilityService.armCapture(
                                resetLast = false,
                                requireInput = false
                            )
                            continue
                        }
                        return@withTimeoutOrNull norm
                    }
                    @Suppress("UNREACHABLE_CODE")
                    null
                } ?: fallbackPrompt
                if (next.isNullOrBlank()) {
                    return@withContext Result.failure(
                        IllegalStateException(
                            "USSD step ${index + 1} ('$step') sent but no next prompt captured"
                        )
                    )
                }
                transcript += next
                RouteBotLog.i(
                    "ussd_step_prompt",
                    mapOf("n" to index + 1, "input" to step, "chars" to next.length)
                )
            }

            delay(400)
            UssdAccessibilityService.dismissDialog()
            Result.success(transcript.joinToString("\n---\n"))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            UssdAccessibilityService.cancelCapture()
        }
    }

    private fun placeUssdCall(code: String, subscriptionId: Int?) {
        val uri = Uri.fromParts("tel", code, null)
        val extras = Bundle()
        phoneAccountHandle(subscriptionId)?.let {
            extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it)
        }

        val telecom = context.getSystemService(TelecomManager::class.java)
        if (telecom != null &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
            == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                telecom.placeCall(uri, extras)
                RouteBotLog.i("ussd_place_call", mapOf("code" to code))
                return
            } catch (e: Exception) {
                RouteBotLog.w("ussd_place_call_failed", mapOf("error" to (e.message ?: "")))
            }
        }

        val intent = Intent(Intent.ACTION_CALL, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val slot = slotIndexForSubscription(subscriptionId)
            if (slot != null) {
                putExtra("com.android.phone.extra.slot", slot)
                putExtra("slot", slot)
                putExtra("simSlot", slot)
            }
        }
        context.startActivity(intent)
        RouteBotLog.i("ussd_action_call", mapOf("code" to code))
    }

    private fun telephonyFor(subscriptionId: Int?): TelephonyManager {
        return if (subscriptionId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            telephonyManager.createForSubscriptionId(subscriptionId)
        } else {
            telephonyManager
        }
    }

    private fun phoneAccountHandle(subscriptionId: Int?): PhoneAccountHandle? {
        if (subscriptionId == null) return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return null
        return telecom.callCapablePhoneAccounts.firstOrNull { handle ->
            handle.id.contains(subscriptionId.toString())
        }
    }

    private fun slotIndexForSubscription(subscriptionId: Int?): Int? {
        if (subscriptionId == null) return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        return try {
            val sm = context.getSystemService(SubscriptionManager::class.java) ?: return null
            sm.getActiveSubscriptionInfo(subscriptionId)?.simSlotIndex
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val API_TIMEOUT_MS = 12_000L
        private const val DIAL_CAPTURE_TIMEOUT_MS = 45_000L
        private const val STEP_CAPTURE_TIMEOUT_MS = 45_000L

        fun normalizeCode(raw: String): String? {
            val trimmed = raw.trim().replace(" ", "")
            if (trimmed.isEmpty()) return null
            return if (trimmed.endsWith("#")) trimmed else "$trimmed#"
        }

        private fun looksLikePinEcho(norm: String, step: String): Boolean {
            val lines = norm.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
            return lines.size <= 2 &&
                lines.any { it.contains("enter pin", true) } &&
                lines.any { it == step || it.endsWith(step) }
        }

        private fun looksLikePinPrompt(norm: String): Boolean {
            val lower = norm.lowercase()
            return lower.contains("enter pin") ||
                (lower.contains("enter") && lower.contains("pin"))
        }

        private fun looksLikeUssdResult(norm: String): Boolean {
            val lower = norm.lowercase()
            return lower.contains("balance") ||
                lower.contains("tk ") ||
                Regex("""(?i)\btk\s*[\d.,]+""").containsMatchIn(norm) ||
                lower.contains("success") ||
                lower.contains("successful") ||
                lower.contains("invalid") ||
                lower.contains("incorrect") ||
                lower.contains("error") ||
                lower.contains("failed") ||
                lower.contains("thank")
        }
    }
}

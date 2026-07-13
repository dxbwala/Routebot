package com.routedns.routebot.ussd

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import com.routedns.routebot.common.RouteBotLog
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * USSD helper using [TelephonyManager.sendUssdRequest].
 *
 * ## Platform limitations
 *
 * - **API 26+ only**: [TelephonyManager.sendUssdRequest] requires Android 8.0 (API 26).
 *   On older devices this helper returns a failure immediately.
 * - **Carrier / OEM variance**: Many manufacturers disable or restrict USSD from third-party apps.
 *   Dual-SIM routing may ignore the requested subscription on some devices.
 * - **No session continuation**: Multi-step USSD menus (interactive sessions) are not supported;
 *   only single-shot requests with a one-shot response callback are handled.
 * - **Permissions**: [android.Manifest.permission.CALL_PHONE] may be required on some OEM builds
 *   even though the API does not document it consistently.
 * - **Background restrictions**: USSD initiated while the app is backgrounded may fail silently
 *   on Android 10+ depending on OEM policy.
 *
 * Always treat USSD as best-effort. Surface failures to the server via command ack.
 */
@Singleton
class UssdHelper @Inject constructor(
    private val telephonyManager: TelephonyManager
) {
    suspend fun sendUssd(code: String, subscriptionId: Int? = null): Result<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return Result.failure(IllegalStateException("USSD requires API 26+"))
        }
        return suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            val tm = if (subscriptionId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                telephonyManager.createForSubscriptionId(subscriptionId)
            } else {
                telephonyManager
            }
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
                            RouteBotLog.w("ussd_failed", mapOf("code" to failureCode))
                            if (cont.isActive) {
                                cont.resume(Result.failure(IllegalStateException("USSD failed: $failureCode")))
                            }
                        }
                    },
                    handler
                )
            } catch (e: SecurityException) {
                if (cont.isActive) cont.resume(Result.failure(e))
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(Result.failure(e))
            }
        }
    }
}

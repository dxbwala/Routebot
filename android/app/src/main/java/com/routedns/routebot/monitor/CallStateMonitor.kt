package com.routedns.routebot.monitor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.routedns.routebot.common.RouteBotLog
import com.routedns.routebot.domain.model.CallEvent
import com.routedns.routebot.domain.model.CallType
import com.routedns.routebot.domain.repository.AgentApiRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallStateMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telephonyManager: TelephonyManager,
    private val agentApi: AgentApiRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastState = TelephonyManager.CALL_STATE_IDLE
    private var listenerRegistered = false

    fun start() {
        if (listenerRegistered) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            RouteBotLog.w("call_monitor_no_permission")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = handleState(state)
            }
            telephonyManager.registerTelephonyCallback(context.mainExecutor, callback)
        } else {
            @Suppress("DEPRECATION")
            telephonyManager.listen(object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    handleState(state, phoneNumber)
                }
            }, PhoneStateListener.LISTEN_CALL_STATE)
        }
        listenerRegistered = true
    }

    private fun handleState(state: Int, number: String? = null) {
        val callType = when {
            lastState == TelephonyManager.CALL_STATE_IDLE && state == TelephonyManager.CALL_STATE_RINGING ->
                CallType.INCOMING
            lastState == TelephonyManager.CALL_STATE_IDLE && state == TelephonyManager.CALL_STATE_OFFHOOK ->
                CallType.OUTGOING
            lastState == TelephonyManager.CALL_STATE_RINGING && state == TelephonyManager.CALL_STATE_IDLE ->
                CallType.MISSED
            else -> null
        }
        lastState = state
        callType ?: return
        scope.launch {
            agentApi.sendCall(
                CallEvent(
                    callType = callType,
                    number = number.orEmpty(),
                    state = stateName(state),
                    startedAt = Instant.now().toString()
                )
            )
        }
    }

    private fun stateName(state: Int) = when (state) {
        TelephonyManager.CALL_STATE_RINGING -> "ringing"
        TelephonyManager.CALL_STATE_OFFHOOK -> "offhook"
        else -> "idle"
    }
}

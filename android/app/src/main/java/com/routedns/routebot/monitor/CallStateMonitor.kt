package com.routedns.routebot.monitor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.SubscriptionManager
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
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallStateMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telephonyManager: TelephonyManager,
    private val agentApi: AgentApiRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastStateBySub = ConcurrentHashMap<Int, Int>()
    private var listenerRegistered = false

    fun start() {
        if (listenerRegistered) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            RouteBotLog.w("call_monitor_no_permission")
            return
        }
        val managers = resolveTelephonyManagers()
        for ((subId, tm) in managers) {
            register(subId, tm)
        }
        listenerRegistered = true
        RouteBotLog.i("call_monitor_started", mapOf("subs" to managers.size))
    }

    private fun resolveTelephonyManagers(): List<Pair<Int, TelephonyManager>> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
            return listOf(-1 to telephonyManager)
        }
        val sm = context.getSystemService(SubscriptionManager::class.java)
        val subs = sm?.activeSubscriptionInfoList.orEmpty()
        if (subs.isEmpty()) {
            return listOf(-1 to telephonyManager)
        }
        return subs.map { info ->
            info.subscriptionId to telephonyManager.createForSubscriptionId(info.subscriptionId)
        }
    }

    private fun register(subId: Int, tm: TelephonyManager) {
        lastStateBySub.putIfAbsent(subId, TelephonyManager.CALL_STATE_IDLE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = handleState(subId, state)
            }
            tm.registerTelephonyCallback(context.mainExecutor, callback)
        } else {
            @Suppress("DEPRECATION")
            tm.listen(object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    handleState(subId, state, phoneNumber)
                }
            }, PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    private fun handleState(subId: Int, state: Int, number: String? = null) {
        val lastState = lastStateBySub[subId] ?: TelephonyManager.CALL_STATE_IDLE
        RouteBotLog.d(
            "call_state",
            mapOf("sub" to subId, "from" to stateName(lastState), "to" to stateName(state))
        )
        val callType = when {
            lastState == TelephonyManager.CALL_STATE_IDLE && state == TelephonyManager.CALL_STATE_RINGING ->
                CallType.INCOMING
            lastState == TelephonyManager.CALL_STATE_IDLE && state == TelephonyManager.CALL_STATE_OFFHOOK ->
                CallType.OUTGOING
            lastState == TelephonyManager.CALL_STATE_RINGING && state == TelephonyManager.CALL_STATE_IDLE ->
                CallType.MISSED
            else -> null
        }
        lastStateBySub[subId] = state
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
            RouteBotLog.i("call_event_sent", mapOf("type" to callType.name, "sub" to subId))
        }
    }

    private fun stateName(state: Int) = when (state) {
        TelephonyManager.CALL_STATE_RINGING -> "ringing"
        TelephonyManager.CALL_STATE_OFFHOOK -> "offhook"
        else -> "idle"
    }
}

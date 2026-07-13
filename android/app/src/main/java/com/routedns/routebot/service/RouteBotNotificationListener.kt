package com.routedns.routebot.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.routedns.routebot.common.RouteBotLog
import com.routedns.routebot.domain.model.NotificationEvent
import com.routedns.routebot.domain.repository.AgentApiRepository
import com.routedns.routebot.domain.repository.SecureStorageRepository
import com.routedns.routebot.domain.usecase.ExtractOtpUseCase
import com.routedns.routebot.domain.model.OtpSource
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

@AndroidEntryPoint
class RouteBotNotificationListener : NotificationListenerService() {

    @Inject lateinit var agentApi: AgentApiRepository
    @Inject lateinit var secureStorage: SecureStorageRepository
    @Inject lateinit var extractOtp: ExtractOtpUseCase

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        scope.launch {
            val allowed = secureStorage.getNotificationPackages()
            val pkg = sbn.packageName
            if (allowed.isNotEmpty() && pkg !in allowed) return@launch

            val extras = sbn.notification.extras
            val title = extras.getCharSequence("android.title")?.toString().orEmpty()
            val text = extras.getCharSequence("android.text")?.toString().orEmpty()
            val event = NotificationEvent(
                packageName = pkg,
                title = title,
                text = text,
                postedAt = Instant.now().toString()
            )
            agentApi.sendNotification(event)

            val combined = "$title $text"
            extractOtp(pkg, combined, OtpSource.NOTIFICATION)?.let { otp ->
                agentApi.sendOtp(otp)
            }
            RouteBotLog.d("notification_forwarded", mapOf("package" to pkg))
        }
    }
}

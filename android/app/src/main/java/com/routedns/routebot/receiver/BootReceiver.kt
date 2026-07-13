package com.routedns.routebot.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.routedns.routebot.domain.repository.SecureStorageRepository
import com.routedns.routebot.service.AgentServiceController
import dagger.hilt.android.EntryPointAccessors

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface BootReceiverEntryPoint {
    fun secureStorage(): SecureStorageRepository
    fun serviceController(): AgentServiceController
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val entry = EntryPointAccessors.fromApplication(context, BootReceiverEntryPoint::class.java)
        Thread {
            try {
                if (kotlinx.coroutines.runBlocking { entry.secureStorage().isRegistered() }) {
                    entry.serviceController().startAgent()
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}

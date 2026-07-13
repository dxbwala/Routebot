package com.routedns.routebot.core.monitor

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory cache of discovered MSISDNs keyed by Android [subscriptionId].
 * Filled from telephony and/or USSD `*2#` discovery; [DeviceMonitor] reads it on each collect.
 */
@Singleton
class SimPhoneCache @Inject constructor() {
    private val bySubscriptionId = ConcurrentHashMap<Int, String>()

    fun get(subscriptionId: Int): String? = bySubscriptionId[subscriptionId]

    fun put(subscriptionId: Int, phoneNumber: String) {
        val cleaned = phoneNumber.trim()
        if (cleaned.isNotEmpty()) {
            bySubscriptionId[subscriptionId] = cleaned
        }
    }

    fun putAll(numbers: Map<Int, String>) {
        numbers.forEach { (id, number) -> put(id, number) }
    }

    fun snapshot(): Map<Int, String> = bySubscriptionId.toMap()
}

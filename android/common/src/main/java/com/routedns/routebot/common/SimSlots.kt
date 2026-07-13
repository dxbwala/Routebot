package com.routedns.routebot.common

/**
 * RouteBot API / dashboard SIM numbering is 1-based:
 * - [SIM_1] = physical SIM 1 (Android `simSlotIndex` 0)
 * - [SIM_2] = physical SIM 2 (Android `simSlotIndex` 1)
 *
 * Android Telephony APIs remain 0-based internally.
 */
object SimSlots {
    const val SIM_1 = 1
    const val SIM_2 = 2
    const val DEFAULT = SIM_1

    /** Convert API `sim_slot` (1|2) to Android physical slot index (0|1). */
    fun toAndroidSlotIndex(apiSimSlot: Int): Int = when {
        apiSimSlot >= SIM_1 -> apiSimSlot - 1
        else -> 0 // legacy 0 → SIM 1
    }

    /** Convert Android physical slot index (0|1) to API `sim_slot` (1|2). */
    fun toApiSimSlot(androidSlotIndex: Int): Int =
        if (androidSlotIndex < 0) DEFAULT else androidSlotIndex + 1

    /** Normalize API payload: accept 1|2, map legacy 0 → SIM 1. */
    fun normalizeApiSimSlot(apiSimSlot: Int): Int =
        if (apiSimSlot >= SIM_1) apiSimSlot else DEFAULT
}

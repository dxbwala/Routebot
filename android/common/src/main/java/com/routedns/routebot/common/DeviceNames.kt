package com.routedns.routebot.common

import android.os.Build

/** Stable operator-facing device label derived from the handset. */
object DeviceNames {
    fun auto(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        return when {
            manufacturer.isNotEmpty() && model.isNotEmpty() &&
                !model.contains(manufacturer, ignoreCase = true) ->
                "$manufacturer $model"
            model.isNotEmpty() -> model
            manufacturer.isNotEmpty() -> manufacturer
            else -> "Android Device"
        }
    }
}

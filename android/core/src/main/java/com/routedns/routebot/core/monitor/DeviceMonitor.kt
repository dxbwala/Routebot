package com.routedns.routebot.core.monitor

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.telephony.TelephonyManager
import com.routedns.routebot.domain.model.DeviceHealthSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun collect(appVersion: String): DeviceHealthSnapshot {
        val battery = readBattery()
        val network = readNetwork()
        return DeviceHealthSnapshot(
            batteryLevel = battery.first,
            isCharging = battery.second,
            storageFreeMb = readStorageFreeMb(),
            ramFreeMb = readRamFreeMb(),
            networkType = network.first,
            wifiSsid = network.second,
            signalStrength = readSignalStrength(),
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            androidVersion = Build.VERSION.RELEASE.orEmpty(),
            appVersion = appVersion
        )
    }

    fun toHeartbeatFields(snapshot: DeviceHealthSnapshot) = snapshot

    private fun readBattery(): Pair<Int, Boolean> {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return pct to charging
    }

    private fun readStorageFreeMb(): Long {
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
        return freeBytes / (1024 * 1024)
    }

    private fun readRamFreeMb(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem / (1024 * 1024)
    }

    private fun readNetwork(): Pair<String, String> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return "none" to ""
        val caps = cm.getNetworkCapabilities(network) ?: return "unknown" to ""
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "unknown"
        }
        val ssid = if (type == "wifi") readWifiSsid() else ""
        return type to ssid
    }

    @Suppress("DEPRECATION")
    private fun readWifiSsid(): String {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wm.connectionInfo ?: return ""
            val raw = info.ssid?.trim('"').orEmpty()
            if (raw == "<unknown ssid>") "" else raw
        } catch (_: SecurityException) {
            ""
        }
    }

    private fun readSignalStrength(): Int? {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                tm.signalStrength?.level
            } else {
                null
            }
        } catch (_: SecurityException) {
            null
        }
    }
}

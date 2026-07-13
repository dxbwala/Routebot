package com.routedns.routebot.core.monitor

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.routedns.routebot.domain.model.DeviceHealthSnapshot
import com.routedns.routebot.domain.model.SimSlotInfo
import com.routedns.routebot.common.SimSlots
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telephonyManager: TelephonyManager,
    private val simPhoneCache: SimPhoneCache
) {
    fun collect(appVersion: String): DeviceHealthSnapshot {
        val battery = readBattery()
        val network = readNetwork()
        return DeviceHealthSnapshot(
            batteryLevel = battery.first,
            isCharging = battery.second,
            storageFreeMb = readStorageFreeMb(),
            ramFreeMb = readRamFreeMb(),
            cpuUsage = readCpuUsagePercent(),
            networkType = network.first,
            wifiSsid = network.second,
            signalStrength = readSignalStrength(),
            simInfo = readSimInfo(),
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            androidVersion = Build.VERSION.RELEASE.orEmpty(),
            appVersion = appVersion
        )
    }

    /**
     * Best-effort overall CPU usage percentage derived from two `/proc/stat` samples.
     * Access to `/proc/stat` for system-wide CPU stats is not guaranteed on all Android
     * versions/OEMs (SELinux policy varies); returns null when unavailable rather than
     * a misleading value. No root or hidden APIs are used.
     */
    private fun readCpuUsagePercent(): Double? {
        return try {
            val first = readProcStatSample() ?: return null
            Thread.sleep(200)
            val second = readProcStatSample() ?: return null
            val idleDelta = (second.idle - first.idle).toDouble()
            val totalDelta = (second.total - first.total).toDouble()
            if (totalDelta <= 0) return null
            (1.0 - (idleDelta / totalDelta)) * 100.0
        } catch (_: Exception) {
            null
        }
    }

    private data class ProcStatSample(val idle: Long, val total: Long)

    private fun readProcStatSample(): ProcStatSample? {
        return try {
            RandomAccessFile("/proc/stat", "r").use { file ->
                val line = file.readLine() ?: return null
                val parts = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
                if (parts.size < 4) return null
                val idle = parts[3]
                val total = parts.sum()
                ProcStatSample(idle, total)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Active SIM slots. Phone numbers are filled from telephony when available, otherwise from
     * [SimPhoneCache] (USSD `*2#` discovery). Never blocks heartbeats on missing numbers.
     */
    private fun readSimInfo(): List<SimSlotInfo> {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }
        return try {
            val sm = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
            sm.activeSubscriptionInfoList.orEmpty().mapIndexed { index, info ->
                val androidSlot = info.simSlotIndex.takeIf { it >= 0 } ?: index
                val phone = resolvePhoneNumber(info.subscriptionId, info)
                SimSlotInfo(
                    slotIndex = SimSlots.toApiSimSlot(androidSlot),
                    subscriptionId = info.subscriptionId,
                    carrierName = info.carrierName?.toString().orEmpty(),
                    displayName = info.displayName?.toString().orEmpty(),
                    isEmbedded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.isEmbedded else false,
                    phoneNumber = phone
                )
            }
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Suppress("DEPRECATION")
    private fun resolvePhoneNumber(
        subscriptionId: Int,
        info: android.telephony.SubscriptionInfo
    ): String {
        val cached = simPhoneCache.get(subscriptionId).orEmpty().trim()
        if (cached.isNotEmpty()) return cached

        val fromSub = info.number?.trim().orEmpty()
        if (looksLikePhone(fromSub)) {
            simPhoneCache.put(subscriptionId, fromSub)
            return fromSub
        }

        return try {
            val tm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                telephonyManager.createForSubscriptionId(subscriptionId)
            } else {
                telephonyManager
            }
            val line1 = tm.line1Number?.trim().orEmpty()
            if (looksLikePhone(line1)) {
                simPhoneCache.put(subscriptionId, line1)
                line1
            } else {
                ""
            }
        } catch (_: SecurityException) {
            ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun looksLikePhone(raw: String): Boolean {
        val digits = raw.filter { it.isDigit() }
        return digits.length in 8..15
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

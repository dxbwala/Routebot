package com.routedns.routebot.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.routedns.routebot.common.Constants
import com.routedns.routebot.common.RouteBotLog
import com.routedns.routebot.domain.repository.SecureStorageRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores sensitive agent credentials using Android Keystore-backed
 * [EncryptedSharedPreferences]. API keys and JWT tokens never touch plaintext storage.
 */
@Singleton
class SecureStorageRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : SecureStorageRepository {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            Constants.PREFS_SECURE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    init {
        ensureKeystoreKey()
    }

    private fun ensureKeystoreKey() {
        try {
            val alias = "routebot_master_key"
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!ks.containsAlias(alias)) {
                val spec = KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                    .apply { init(spec) }
                    .generateKey()
            }
        } catch (e: Exception) {
            RouteBotLog.w("keystore_init_failed", throwable = e)
        }
    }

    override suspend fun saveApiKey(apiKey: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString(Constants.KEY_API_KEY, apiKey).apply()
    }

    override suspend fun getApiKey(): String? = withContext(Dispatchers.IO) {
        prefs.getString(Constants.KEY_API_KEY, null)
    }

    override suspend fun saveServerUrl(url: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString(Constants.KEY_SERVER_URL, url).apply()
    }

    override suspend fun getServerUrl(): String? = withContext(Dispatchers.IO) {
        prefs.getString(Constants.KEY_SERVER_URL, null)
    }

    override suspend fun saveDeviceId(deviceId: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString(Constants.KEY_DEVICE_ID, deviceId).apply()
    }

    override suspend fun getDeviceId(): String? = withContext(Dispatchers.IO) {
        prefs.getString(Constants.KEY_DEVICE_ID, null)
    }

    override suspend fun saveDeviceUuid(uuid: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString(Constants.KEY_DEVICE_UUID, uuid).apply()
    }

    override suspend fun getDeviceUuid(): String? = withContext(Dispatchers.IO) {
        prefs.getString(Constants.KEY_DEVICE_UUID, null)
    }

    override suspend fun saveDeviceName(name: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString(Constants.KEY_DEVICE_NAME, name).apply()
    }

    override suspend fun getDeviceName(): String? = withContext(Dispatchers.IO) {
        prefs.getString(Constants.KEY_DEVICE_NAME, null)
    }

    override suspend fun saveJwtAccess(token: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString(Constants.KEY_JWT_ACCESS, token).apply()
    }

    override suspend fun getJwtAccess(): String? = withContext(Dispatchers.IO) {
        prefs.getString(Constants.KEY_JWT_ACCESS, null)
    }

    override suspend fun saveOtpPatterns(patterns: List<String>) = withContext(Dispatchers.IO) {
        prefs.edit().putString(Constants.KEY_OTP_PATTERNS, patterns.joinToString("\n")).apply()
    }

    override suspend fun getOtpPatterns(): List<String> = withContext(Dispatchers.IO) {
        val raw = prefs.getString(Constants.KEY_OTP_PATTERNS, null)
        if (raw.isNullOrBlank()) Constants.DEFAULT_OTP_PATTERNS else raw.lines().filter { it.isNotBlank() }
    }

    override suspend fun saveNotificationPackages(packages: List<String>) = withContext(Dispatchers.IO) {
        prefs.edit().putString(Constants.KEY_NOTIFICATION_PACKAGES, packages.joinToString(",")).apply()
    }

    override suspend fun getNotificationPackages(): List<String> = withContext(Dispatchers.IO) {
        prefs.getString(Constants.KEY_NOTIFICATION_PACKAGES, null)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
    }

    override suspend fun saveCertificatePins(pins: List<String>) = withContext(Dispatchers.IO) {
        prefs.edit().putString(Constants.KEY_CERTIFICATE_PINS, pins.joinToString("\n")).apply()
    }

    override suspend fun getCertificatePins(): List<String> = withContext(Dispatchers.IO) {
        prefs.getString(Constants.KEY_CERTIFICATE_PINS, null)
            ?.lines()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
    }

    override suspend fun isRegistered(): Boolean = withContext(Dispatchers.IO) {
        !getApiKey().isNullOrBlank() && !getServerUrl().isNullOrBlank()
    }

    override suspend fun clearAll() = withContext(Dispatchers.IO) {
        prefs.edit().clear().apply()
    }
}

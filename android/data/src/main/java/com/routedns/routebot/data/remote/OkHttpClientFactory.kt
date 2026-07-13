package com.routedns.routebot.data.remote

import com.routedns.routebot.common.Constants
import com.routedns.routebot.common.RouteBotLog
import com.routedns.routebot.domain.repository.SecureStorageRepository
import kotlinx.coroutines.runBlocking
import okhttp3.CertificatePinner
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import okio.Buffer
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OkHttpClientFactory @Inject constructor(
    private val secureStorage: SecureStorageRepository
) {
    fun create(baseUrl: String): OkHttpClient {
        val host = runCatching {
            java.net.URI(baseUrl.trimEnd('/')).host
        }.getOrNull() ?: return defaultClient(null)

        val pins = runBlocking { secureStorage.getCertificatePins() }
        val pinner = buildCertificatePinner(host, pins)
        return defaultClient(pinner)
    }

    /**
     * Builds an OkHttp [CertificatePinner] from optional pin strings.
     *
     * Pin format (one per line in config):
     * ```
     * sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
     * ```
     *
     * When no pins are configured, certificate pinning is disabled (TLS still enforced).
     */
    fun buildCertificatePinner(host: String, pins: List<String>): CertificatePinner? {
        if (pins.isEmpty()) return null
        val builder = CertificatePinner.Builder()
        pins.forEach { pin ->
            val normalized = if (pin.startsWith("sha256/")) pin else "sha256/$pin"
            builder.add(host, normalized)
        }
        RouteBotLog.i("certificate_pinner_configured", mapOf("host" to host, "pin_count" to pins.size))
        return builder.build()
    }

    private fun defaultClient(pinner: CertificatePinner?): OkHttpClient {
        val logging = HttpLoggingInterceptor { message ->
            if (!message.contains("api_key", ignoreCase = true) &&
                !message.contains("Authorization", ignoreCase = true)
            ) {
                RouteBotLog.d("http", mapOf("msg" to message.take(200)))
            }
        }.apply { level = HttpLoggingInterceptor.Level.BASIC }

        val requestIdInterceptor = Interceptor { chain ->
            val request = chain.request().newBuilder()
                .header(Constants.HEADER_REQUEST_ID, UUID.randomUUID().toString())
                .build()
            chain.proceed(request)
        }

        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .apply { pinner?.let { certificatePinner(it) } }
            .addInterceptor(requestIdInterceptor)
            .addInterceptor(RequestSigningInterceptor(secureStorage))
            .addInterceptor(logging)
            .build()
    }
}

/**
 * Signs every agent request (any request already carrying [Constants.HEADER_DEVICE_API_KEY])
 * with HMAC-SHA256("timestamp.body", rawApiKey) and attaches [Constants.HEADER_TIMESTAMP] /
 * [Constants.HEADER_SIGNATURE]. Combined with the per-request nonce in
 * [Constants.HEADER_REQUEST_ID], the server verifies both signature and replay protection —
 * see backend `middleware.DeviceAPIKey` / `DeviceService.VerifyRequestSignature`.
 *
 * The raw API key is the same secret the device received once at registration/enrollment and
 * already holds in [SecureStorageRepository]; it is never sent over the wire again.
 */
private class RequestSigningInterceptor(
    private val secureStorage: SecureStorageRepository
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        if (request.header(Constants.HEADER_DEVICE_API_KEY) == null) {
            return chain.proceed(request)
        }
        val apiKey = runBlocking { secureStorage.getApiKey() } ?: return chain.proceed(request)

        val bodyBytes = request.body?.let { body ->
            val buffer = Buffer()
            body.writeTo(buffer)
            buffer.readByteArray()
        } ?: ByteArray(0)

        val timestamp = System.currentTimeMillis() / 1000
        val signature = sign(apiKey, timestamp, bodyBytes)

        val signed = request.newBuilder()
            .header(Constants.HEADER_TIMESTAMP, timestamp.toString())
            .header(Constants.HEADER_SIGNATURE, signature)
            .build()
        return chain.proceed(signed)
    }

    private fun sign(secret: String, timestamp: Long, body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        mac.update(timestamp.toString().toByteArray(Charsets.UTF_8))
        mac.update(".".toByteArray(Charsets.UTF_8))
        val raw = mac.doFinal(body)
        return raw.joinToString("") { "%02x".format(it) }
    }
}

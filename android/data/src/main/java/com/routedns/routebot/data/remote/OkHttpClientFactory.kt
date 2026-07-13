package com.routedns.routebot.data.remote

import com.routedns.routebot.common.Constants
import com.routedns.routebot.common.RouteBotLog
import com.routedns.routebot.domain.repository.SecureStorageRepository
import kotlinx.coroutines.runBlocking
import okhttp3.CertificatePinner
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.UUID
import java.util.concurrent.TimeUnit
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
            .addInterceptor(logging)
            .build()
    }
}

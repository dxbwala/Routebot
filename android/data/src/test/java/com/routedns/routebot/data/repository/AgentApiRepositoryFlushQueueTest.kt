package com.routedns.routebot.data.repository

import com.routedns.routebot.data.remote.OkHttpClientFactory
import com.routedns.routebot.domain.model.EventTypes
import com.routedns.routebot.domain.model.QueuedEvent
import com.routedns.routebot.domain.model.SmsDirection
import com.routedns.routebot.domain.model.SmsMessage
import com.routedns.routebot.domain.repository.OfflineQueueRepository
import com.routedns.routebot.domain.repository.SecureStorageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Regression test for the SMS Gateway race condition: concurrent [AgentApiRepositoryImpl.flushQueue]
 * invocations (triggered independently by WS reconnect and the SYNC remote command) must not
 * dispatch the same queued SMS event to the backend more than once.
 */
class AgentApiRepositoryFlushQueueTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `concurrent flushQueue calls do not dispatch the same queued sms twice`() = runTest {
        val requestCount = AtomicLong(0)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                if (request.path?.endsWith("/api/v1/agent/sms") == true) {
                    requestCount.incrementAndGet()
                }
                return MockResponse().setResponseCode(200)
                    .setBody("""{"success":true,"data":{}}""")
            }
        }

        val baseUrl = server.url("/").toString()
        val secureStorage = FakeSecureStorageRepository(serverUrl = baseUrl, apiKey = "test-key")
        val jsonProvider = JsonProvider()
        val offlineQueue = InMemoryOfflineQueueRepository()
        offlineQueue.enqueue(
            EventTypes.SMS,
            jsonProvider.json.encodeToString(
                SmsMessage(direction = SmsDirection.INBOUND, address = "+1555", body = "code 123456")
            )
        )

        val repo = AgentApiRepositoryImpl(
            secureStorage = secureStorage,
            okHttpFactory = OkHttpClientFactory(secureStorage),
            jsonProvider = jsonProvider,
            offlineQueue = offlineQueue
        )

        // Simulate the two independent triggers (WS reconnect, SYNC command) firing at once.
        val first = async(Dispatchers.IO) { repo.flushQueue() }
        val second = async(Dispatchers.IO) { repo.flushQueue() }
        first.await()
        second.await()

        assertEquals(1, requestCount.get())
        assertEquals(0, offlineQueue.peekBatch().size)
    }

    private class FakeSecureStorageRepository(
        private val serverUrl: String,
        private val apiKey: String
    ) : SecureStorageRepository {
        override suspend fun saveApiKey(apiKey: String) {}
        override suspend fun getApiKey(): String? = apiKey
        override suspend fun saveServerUrl(url: String) {}
        override suspend fun getServerUrl(): String? = serverUrl
        override suspend fun saveDeviceId(deviceId: String) {}
        override suspend fun getDeviceId(): String? = null
        override suspend fun saveDeviceUuid(uuid: String) {}
        override suspend fun getDeviceUuid(): String? = null
        override suspend fun saveDeviceName(name: String) {}
        override suspend fun getDeviceName(): String? = null
        override suspend fun saveJwtAccess(token: String) {}
        override suspend fun getJwtAccess(): String? = null
        override suspend fun saveOtpPatterns(patterns: List<String>) {}
        override suspend fun getOtpPatterns(): List<String> = emptyList()
        override suspend fun saveNotificationPackages(packages: List<String>) {}
        override suspend fun getNotificationPackages(): List<String> = emptyList()
        override suspend fun saveCertificatePins(pins: List<String>) {}
        override suspend fun getCertificatePins(): List<String> = emptyList()
        override suspend fun isRegistered(): Boolean = true
        override suspend fun clearAll() {}
    }

    private class InMemoryOfflineQueueRepository : OfflineQueueRepository {
        private val idSeq = AtomicLong(1)
        private val items = CopyOnWriteArrayList<QueuedEvent>()
        private val countFlow = MutableStateFlow(0)

        override suspend fun enqueue(type: String, payload: String) {
            items.add(QueuedEvent(id = idSeq.getAndIncrement(), type = type, payload = payload))
            countFlow.value = items.size
        }

        override suspend fun peekBatch(limit: Int): List<QueuedEvent> =
            items.sortedBy { it.createdAt }.take(limit)

        override suspend fun markSent(id: Long) {
            items.removeAll { it.id == id }
            countFlow.value = items.size
        }

        override suspend fun markFailed(id: Long) {
            val idx = items.indexOfFirst { it.id == id }
            if (idx >= 0) items[idx] = items[idx].copy(retryCount = items[idx].retryCount + 1)
        }

        override fun observePendingCount(): Flow<Int> = countFlow
    }
}

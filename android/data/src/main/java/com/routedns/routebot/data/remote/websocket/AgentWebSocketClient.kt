package com.routedns.routebot.data.remote.websocket

import com.routedns.routebot.common.Constants
import com.routedns.routebot.common.RouteBotLog
import com.routedns.routebot.domain.model.RemoteCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

enum class ConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING
}

@Singleton
class AgentWebSocketClient @Inject constructor(
    private val json: Json
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var webSocket: WebSocket? = null
    private var client: OkHttpClient? = null
    private var connectJob: Job? = null
    private var pingJob: Job? = null

    private val backoffMs = AtomicLong(Constants.WS_INITIAL_BACKOFF_MS)

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _commands = MutableSharedFlow<RemoteCommand>(extraBufferCapacity = 32)
    val commands: SharedFlow<RemoteCommand> = _commands.asSharedFlow()

    private val _connected = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val connected: SharedFlow<Unit> = _connected.asSharedFlow()

    fun connect(serverUrl: String, apiKey: String, okHttpClient: OkHttpClient) {
        disconnect()
        client = okHttpClient
        backoffMs.set(Constants.WS_INITIAL_BACKOFF_MS)
        connectJob = scope.launch { connectLoop(serverUrl, apiKey) }
    }

    fun disconnect() {
        connectJob?.cancel()
        pingJob?.cancel()
        webSocket?.close(1000, "client_disconnect")
        webSocket = null
        _state.value = ConnectionState.DISCONNECTED
    }

    fun sendPing() {
        webSocket?.send("""{"type":"ping"}""")
    }

    fun sendHeartbeat() {
        webSocket?.send("""{"type":"heartbeat"}""")
    }

    private suspend fun connectLoop(serverUrl: String, apiKey: String) {
        val base = serverUrl.trimEnd('/')
        val wsUrl = base.replace("https://", "wss://").replace("http://", "ws://")
        val encodedKey = java.net.URLEncoder.encode(apiKey, Charsets.UTF_8.name())
        val requestUrl = "$wsUrl/ws/agent?api_key=$encodedKey"

        while (scope.isActive) {
            _state.value = if (backoffMs.get() > Constants.WS_INITIAL_BACKOFF_MS) {
                ConnectionState.RECONNECTING
            } else {
                ConnectionState.CONNECTING
            }

            val request = Request.Builder().url(requestUrl).build()
            val latch = kotlinx.coroutines.CompletableDeferred<Boolean>()

            webSocket = client?.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    RouteBotLog.i("ws_connected")
                    backoffMs.set(Constants.WS_INITIAL_BACKOFF_MS)
                    _state.value = ConnectionState.CONNECTED
                    _connected.tryEmit(Unit)
                    startPingLoop()
                    latch.complete(true)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    RouteBotLog.i("ws_closed", mapOf("code" to code, "reason" to reason))
                    pingJob?.cancel()
                    _state.value = ConnectionState.DISCONNECTED
                    latch.complete(false)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    RouteBotLog.w(
                        "ws_failure",
                        mapOf("code" to (response?.code ?: -1), "message" to (t.message ?: "")),
                        t
                    )
                    pingJob?.cancel()
                    _state.value = ConnectionState.DISCONNECTED
                    latch.complete(false)
                }
            })

            val success = runCatching { latch.await() }.getOrDefault(false)
            if (!success) {
                val wait = backoffMs.get()
                RouteBotLog.i("ws_reconnect_backoff", mapOf("ms" to wait))
                delay(wait)
                backoffMs.set(min(wait * 2, Constants.WS_MAX_BACKOFF_MS))
            } else {
                // Block until disconnect triggers reconnect
                while (_state.value == ConnectionState.CONNECTED && scope.isActive) {
                    delay(1_000)
                }
                if (scope.isActive) {
                    val wait = backoffMs.get()
                    delay(wait)
                    backoffMs.set(min(wait * 2, Constants.WS_MAX_BACKOFF_MS))
                }
            }
        }
    }

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive && _state.value == ConnectionState.CONNECTED) {
                sendPing()
                delay(Constants.WS_PING_INTERVAL_MS)
            }
        }
    }

    private fun handleMessage(text: String) {
        runCatching {
            val element = json.parseToJsonElement(text)
            val obj = element.jsonObject
            when (obj["type"]?.jsonPrimitive?.content) {
                "command" -> {
                    val id = obj["id"]?.jsonPrimitive?.content ?: return
                    val command = obj["command"]?.jsonPrimitive?.content ?: return
                    val payload = obj["payload"]
                    _commands.tryEmit(RemoteCommand(id, command, payload))
                }
                "pong", "heartbeat_ack", "welcome" -> Unit
                "error" -> {
                    val message = obj["message"]?.jsonPrimitive?.content ?: "unknown"
                    RouteBotLog.w("ws_server_error", mapOf("message" to message))
                    // Force reconnect path; auth errors need a new enrollment.
                    webSocket?.close(1008, message)
                }
                else -> RouteBotLog.d("ws_message", mapOf("type" to obj["type"]))
            }
        }.onFailure {
            RouteBotLog.w("ws_parse_error", throwable = it)
        }
    }

    fun shutdown() {
        disconnect()
        scope.cancel()
    }
}

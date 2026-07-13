package com.routedns.routebot.ui.dashboard

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.routedns.routebot.BuildConfig
import com.routedns.routebot.core.monitor.DeviceMonitor
import com.routedns.routebot.data.remote.websocket.AgentWebSocketClient
import com.routedns.routebot.data.remote.websocket.ConnectionState
import com.routedns.routebot.domain.repository.OfflineQueueRepository
import com.routedns.routebot.domain.repository.SecureStorageRepository
import com.routedns.routebot.monitor.CallStateMonitor
import com.routedns.routebot.service.AgentServiceController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class DashboardUiState(
    val deviceName: String = "",
    val serverUrl: String = "",
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val lastHeartbeat: String = "—",
    val pendingEvents: Int = 0,
    val battery: Int = -1,
    val network: String = "unknown",
    val permissions: List<PermissionStatus> = emptyList()
)

data class PermissionStatus(val name: String, val granted: Boolean)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val secureStorage: SecureStorageRepository,
    private val webSocketClient: AgentWebSocketClient,
    private val offlineQueue: OfflineQueueRepository,
    private val deviceMonitor: DeviceMonitor,
    private val serviceController: AgentServiceController,
    private val callStateMonitor: CallStateMonitor
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { refresh() }
        viewModelScope.launch {
            webSocketClient.state.collect { state ->
                _uiState.value = _uiState.value.copy(connectionState = state)
            }
        }
        viewModelScope.launch {
            offlineQueue.observePendingCount().collect { count ->
                _uiState.value = _uiState.value.copy(pendingEvents = count)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            serviceController.startAgent()
            callStateMonitor.start()
            val snapshot = deviceMonitor.collect(BuildConfig.VERSION_NAME)
            _uiState.value = DashboardUiState(
                deviceName = secureStorage.getDeviceName().orEmpty(),
                serverUrl = secureStorage.getServerUrl().orEmpty(),
                connectionState = webSocketClient.state.value,
                lastHeartbeat = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()),
                battery = snapshot.batteryLevel,
                network = snapshot.networkType
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(viewModel: DashboardViewModel, onSettings: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val permissions = listOf(
        Manifest.permission.RECEIVE_SMS to "SMS Receive",
        Manifest.permission.SEND_SMS to "SMS Send",
        Manifest.permission.READ_PHONE_STATE to "Phone State",
        Manifest.permission.POST_NOTIFICATIONS to "Notifications",
        Manifest.permission.RECORD_AUDIO to "Audio",
        Manifest.permission.CAMERA to "Camera"
    ).map { (perm, label) ->
        PermissionStatus(label, ContextCompat.checkSelfPermission(context, perm) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RouteBot") },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Device: ${state.deviceName.ifBlank { "Unnamed" }}", style = MaterialTheme.typography.titleMedium)
                    Text("Server: ${state.serverUrl}")
                    Text("WebSocket: ${state.connectionState.name}")
                    Text("Last heartbeat: ${state.lastHeartbeat}")
                    Text("Pending offline events: ${state.pendingEvents}")
                    Text("Battery: ${if (state.battery >= 0) "${state.battery}%" else "n/a"}")
                    Text("Network: ${state.network}")
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Permissions", style = MaterialTheme.typography.titleMedium)
                    permissions.forEach { perm ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(perm.name)
                            Text(if (perm.granted) "✓" else "✗")
                        }
                    }
                    Text(
                        "Notification access: enable in system settings",
                        style = MaterialTheme.typography.bodySmall
                    )
                    androidx.compose.material3.TextButton(
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }
                    ) { Text("Open Notification Access") }
                }
            }
        }
    }
}

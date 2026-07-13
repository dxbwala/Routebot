package com.routedns.routebot.ui.setup

import android.Manifest
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.routedns.routebot.BuildConfig
import com.routedns.routebot.domain.model.DeviceRegistrationRequest
import com.routedns.routebot.domain.repository.SecureStorageRepository
import com.routedns.routebot.domain.usecase.IsDeviceRegisteredUseCase
import com.routedns.routebot.domain.usecase.LoginUseCase
import com.routedns.routebot.domain.usecase.RegisterDeviceUseCase
import com.routedns.routebot.domain.usecase.RegisterWithApiKeyUseCase
import com.routedns.routebot.service.AgentServiceController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class SetupUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val success: Boolean = false
)

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val secureStorage: SecureStorageRepository,
    private val loginUseCase: LoginUseCase,
    private val registerDeviceUseCase: RegisterDeviceUseCase,
    private val registerWithApiKeyUseCase: RegisterWithApiKeyUseCase,
    private val isRegisteredUseCase: IsDeviceRegisteredUseCase,
    private val serviceController: AgentServiceController
) : ViewModel() {

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    val isRegistered: StateFlow<Boolean> = kotlinx.coroutines.flow.flow {
        emit(isRegisteredUseCase())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun registerWithCredentials(
        serverUrl: String,
        email: String,
        password: String,
        deviceName: String
    ) {
        viewModelScope.launch {
            _uiState.value = SetupUiState(loading = true)
            secureStorage.saveServerUrl(serverUrl.trimEnd('/'))
            val login = loginUseCase(email, password)
            login.onError { msg, _ ->
                _uiState.value = SetupUiState(error = msg)
                return@launch
            }
            val token = login.getOrNull()?.accessToken ?: return@launch
            val uuid = secureStorage.getDeviceUuid() ?: UUID.randomUUID().toString().also {
                secureStorage.saveDeviceUuid(it)
            }
            val result = registerDeviceUseCase(
                token,
                DeviceRegistrationRequest(
                    deviceUuid = uuid,
                    name = deviceName.ifBlank { "RouteBot Device" },
                    manufacturer = android.os.Build.MANUFACTURER,
                    model = android.os.Build.MODEL,
                    androidVersion = android.os.Build.VERSION.RELEASE,
                    appVersion = BuildConfig.VERSION_NAME
                )
            )
            result.onSuccess {
                _uiState.value = SetupUiState(success = true)
                serviceController.startAgent()
            }.onError { msg, _ ->
                _uiState.value = SetupUiState(error = msg)
            }
        }
    }

    fun registerWithApiKey(serverUrl: String, apiKey: String, deviceName: String) {
        viewModelScope.launch {
            _uiState.value = SetupUiState(loading = true)
            secureStorage.saveDeviceName(deviceName.ifBlank { "RouteBot Device" })
            val result = registerWithApiKeyUseCase(apiKey, serverUrl)
            result.onSuccess {
                _uiState.value = SetupUiState(success = true)
                serviceController.startAgent()
            }.onError { msg, _ ->
                _uiState.value = SetupUiState(error = msg)
            }
        }
    }
}

@Composable
fun SetupScreen(viewModel: SetupViewModel, onComplete: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var serverUrl by remember { mutableStateOf("https://") }
    var deviceName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }

    if (uiState.success) onComplete()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("RouteBot Setup", style = MaterialTheme.typography.headlineMedium)
        Text("Connect this device to your RouteDNS dashboard.")

        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Credentials") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("API Key") })
        }

        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("Server URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = deviceName,
            onValueChange = { deviceName = it },
            label = { Text("Device Name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        if (tab == 0) {
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Owner Email") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Button(
                onClick = {
                    viewModel.registerWithCredentials(serverUrl, email, password, deviceName)
                },
                enabled = !uiState.loading,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Register with Credentials") }
        } else {
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("Device API Key (from dashboard)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Button(
                onClick = { viewModel.registerWithApiKey(serverUrl, apiKey, deviceName) },
                enabled = !uiState.loading,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Register with API Key") }
        }

        if (uiState.loading) CircularProgressIndicator()
        uiState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

package com.routedns.routebot.ui.setup

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.routedns.routebot.BuildConfig
import com.routedns.routebot.common.Result
import com.routedns.routebot.domain.model.DeviceRegistrationRequest
import com.routedns.routebot.domain.model.EnrollmentQrPayload
import com.routedns.routebot.domain.repository.SecureStorageRepository
import com.routedns.routebot.domain.usecase.ClaimEnrollmentUseCase
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
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject

private const val DEFAULT_SERVER_URL = "https://gateway.routedns.io"

enum class SetupPane { CREDENTIALS, QR, API_KEY }

data class SetupUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val success: Boolean = false,
    val pane: SetupPane = SetupPane.CREDENTIALS
)

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val secureStorage: SecureStorageRepository,
    private val loginUseCase: LoginUseCase,
    private val registerDeviceUseCase: RegisterDeviceUseCase,
    private val registerWithApiKeyUseCase: RegisterWithApiKeyUseCase,
    private val claimEnrollmentUseCase: ClaimEnrollmentUseCase,
    private val isRegisteredUseCase: IsDeviceRegisteredUseCase,
    private val serviceController: AgentServiceController,
    private val json: Json
) : ViewModel() {

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    private val _serverUrl = MutableStateFlow(DEFAULT_SERVER_URL)
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    val isRegistered: StateFlow<Boolean> = kotlinx.coroutines.flow.flow {
        emit(isRegisteredUseCase())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        viewModelScope.launch {
            secureStorage.getServerUrl()?.takeIf { it.isNotBlank() }?.let { _serverUrl.value = it }
        }
    }

    fun saveServerUrl(url: String) {
        val trimmed = url.trim().trimEnd('/')
        if (trimmed.isBlank()) return
        _serverUrl.value = trimmed
        viewModelScope.launch { secureStorage.saveServerUrl(trimmed) }
    }

    fun showPane(pane: SetupPane) {
        _uiState.value = SetupUiState(pane = pane)
    }

    fun signIn(email: String, password: String) {
        viewModelScope.launch {
            _uiState.value = SetupUiState(loading = true, pane = SetupPane.CREDENTIALS)
            secureStorage.saveServerUrl(_serverUrl.value)
            val login = loginUseCase(email, password)
            login.onError { msg, _ ->
                _uiState.value = SetupUiState(error = msg, pane = SetupPane.CREDENTIALS)
                return@launch
            }
            val token = login.getOrNull()?.accessToken ?: return@launch
            val result = registerDeviceUseCase(token, buildDeviceRequest())
            result.onSuccess {
                _uiState.value = SetupUiState(success = true)
                serviceController.startAgent()
            }.onError { msg, _ ->
                _uiState.value = SetupUiState(error = msg, pane = SetupPane.CREDENTIALS)
            }
        }
    }

    fun connectWithApiKey(apiKey: String) {
        viewModelScope.launch {
            _uiState.value = SetupUiState(loading = true, pane = SetupPane.API_KEY)
            val result = registerWithApiKeyUseCase(apiKey, _serverUrl.value)
            result.onSuccess {
                _uiState.value = SetupUiState(success = true)
                serviceController.startAgent()
            }.onError { msg, _ ->
                _uiState.value = SetupUiState(error = msg, pane = SetupPane.API_KEY)
            }
        }
    }

    fun onQrScanned(raw: String) {
        viewModelScope.launch {
            _uiState.value = SetupUiState(loading = true, pane = SetupPane.QR)
            val payload = parseQr(raw)
            if (payload == null) {
                _uiState.value = SetupUiState(error = "Invalid RouteBot QR code", pane = SetupPane.QR)
                return@launch
            }
            val result = claimEnrollmentUseCase(
                serverUrl = payload.serverUrl,
                token = payload.token,
                request = buildDeviceRequest()
            )
            when (result) {
                is Result.Success -> {
                    _serverUrl.value = payload.serverUrl
                    _uiState.value = SetupUiState(success = true)
                    serviceController.startAgent()
                }
                is Result.Error -> {
                    _uiState.value = SetupUiState(error = result.message, pane = SetupPane.QR)
                }
                else -> {
                    _uiState.value = SetupUiState(error = "Enrollment failed", pane = SetupPane.QR)
                }
            }
        }
    }

    fun clearError(pane: SetupPane) {
        _uiState.value = SetupUiState(pane = pane)
    }

    private suspend fun buildDeviceRequest(): DeviceRegistrationRequest {
        val uuid = secureStorage.getDeviceUuid() ?: UUID.randomUUID().toString().also {
            secureStorage.saveDeviceUuid(it)
        }
        return DeviceRegistrationRequest(
            deviceUuid = uuid,
            name = com.routedns.routebot.common.DeviceNames.auto().also {
                secureStorage.saveDeviceName(it)
            },
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE,
            appVersion = BuildConfig.VERSION_NAME
        )
    }

    private fun parseQr(raw: String): EnrollmentQrPayload? {
        return runCatching {
            json.decodeFromString(EnrollmentQrPayload.serializer(), raw.trim())
        }.getOrNull()?.takeIf { it.v == 1 && it.serverUrl.isNotBlank() && it.token.isNotBlank() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(viewModel: SetupViewModel, onComplete: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    val serverUrl by viewModel.serverUrl.collectAsState()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf("") }
    var showServerDialog by remember { mutableStateOf(false) }
    var scannerKey by remember { mutableIntStateOf(0) }

    if (uiState.success) onComplete()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("RouteBot Agent") },
                navigationIcon = {
                    if (uiState.pane != SetupPane.CREDENTIALS) {
                        IconButton(onClick = { viewModel.showPane(SetupPane.CREDENTIALS) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showServerDialog = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Server address")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color(0xFFE1E8FA),
                    titleContentColor = Color(0xFF17347A),
                    navigationIconContentColor = Color(0xFF17347A),
                    actionIconContentColor = Color(0xFF17347A)
                )
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (uiState.pane) {
                SetupPane.CREDENTIALS -> CredentialsPane(
                    email = email,
                    onEmailChange = { email = it },
                    password = password,
                    onPasswordChange = { password = it },
                    passwordVisible = passwordVisible,
                    onTogglePasswordVisible = { passwordVisible = !passwordVisible },
                    loading = uiState.loading,
                    error = uiState.error,
                    onSignIn = { viewModel.signIn(email.trim(), password) },
                    onUseQr = { scannerKey++; viewModel.showPane(SetupPane.QR) },
                    onUseApiKey = { viewModel.showPane(SetupPane.API_KEY) }
                )
                SetupPane.QR -> QrPane(
                    scannerKey = scannerKey,
                    loading = uiState.loading,
                    error = uiState.error,
                    onScanned = { viewModel.onQrScanned(it) },
                    onRetry = { scannerKey++; viewModel.clearError(SetupPane.QR) }
                )
                SetupPane.API_KEY -> ApiKeyPane(
                    apiKey = apiKey,
                    onApiKeyChange = { apiKey = it },
                    loading = uiState.loading,
                    error = uiState.error,
                    onConnect = { viewModel.connectWithApiKey(apiKey.trim()) }
                )
            }
        }
    }

    if (showServerDialog) {
        ServerUrlDialog(
            current = serverUrl,
            onDismiss = { showServerDialog = false },
            onSave = {
                viewModel.saveServerUrl(it)
                showServerDialog = false
            }
        )
    }
}

@Composable
private fun CredentialsPane(
    email: String,
    onEmailChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    passwordVisible: Boolean,
    onTogglePasswordVisible: () -> Unit,
    loading: Boolean,
    error: String?,
    onSignIn: () -> Unit,
    onUseQr: () -> Unit,
    onUseApiKey: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        OutlinedTextField(
            value = email,
            onValueChange = onEmailChange,
            label = { Text("Email Address") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = onTogglePasswordVisible) {
                    Text(if (passwordVisible) "Hide" else "Show")
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(28.dp))

        Button(
            onClick = onSignIn,
            enabled = !loading && email.isNotBlank() && password.isNotBlank(),
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
            } else {
                Text("Sign In")
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        TextButton(onClick = onUseQr) { Text("Sign in using QR code") }
        TextButton(onClick = onUseApiKey) {
            Text(
                "Use device API key instead",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        error?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun QrPane(
    scannerKey: Int,
    loading: Boolean,
    error: String?,
    onScanned: (String) -> Unit,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Scan the QR code from the dashboard", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(16.dp))

        if (!loading && error == null) {
            key(scannerKey) {
                QrScanner(onQrScanned = onScanned, modifier = Modifier.fillMaxWidth())
            }
        }

        if (loading) {
            Spacer(modifier = Modifier.height(24.dp))
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(8.dp))
            Text("Connecting…")
        }

        error?.let {
            Spacer(modifier = Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onRetry) { Text("Scan again") }
        }
    }
}

@Composable
private fun ApiKeyPane(
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    loading: Boolean,
    error: String?,
    onConnect: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Enter Device API Key", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(20.dp))
        OutlinedTextField(
            value = apiKey,
            onValueChange = onApiKeyChange,
            label = { Text("Device API Key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = onConnect,
            enabled = !loading && apiKey.isNotBlank(),
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
            } else {
                Text("Connect")
            }
        }
        error?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ServerUrlDialog(
    current: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var value by remember(current) { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Server Address") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text("Server URL") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(value) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

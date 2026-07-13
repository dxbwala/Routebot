package com.routedns.routebot.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.routedns.routebot.domain.model.AgentConfig
import com.routedns.routebot.domain.repository.ConfigRepository
import com.routedns.routebot.domain.repository.SecureStorageRepository
import com.routedns.routebot.service.AgentServiceController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val configRepository: ConfigRepository,
    private val secureStorage: SecureStorageRepository,
    private val serviceController: AgentServiceController
) : ViewModel() {

    private val _config = MutableStateFlow(AgentConfig())
    val config: StateFlow<AgentConfig> = _config.asStateFlow()

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    init {
        viewModelScope.launch {
            _config.value = configRepository.getConfig()
        }
    }

    fun save(
        serverUrl: String,
        deviceName: String,
        otpPatterns: String,
        notificationPackages: String,
        certificatePins: String
    ) {
        viewModelScope.launch {
            val updated = AgentConfig(
                serverUrl = serverUrl.trimEnd('/'),
                deviceName = deviceName,
                otpPatterns = otpPatterns.lines().filter { it.isNotBlank() },
                notificationPackages = notificationPackages.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                certificatePins = certificatePins.lines().filter { it.isNotBlank() }
            )
            configRepository.updateConfig(updated)
            secureStorage.saveDeviceName(deviceName)
            _config.value = updated
            _saved.value = true
            serviceController.restartAgent()
        }
    }

    fun resetRegistration() {
        viewModelScope.launch {
            secureStorage.clearAll()
            serviceController.stopAgent()
            _config.value = AgentConfig()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val config by viewModel.config.collectAsState()
    val saved by viewModel.saved.collectAsState()

    var serverUrl by remember(config.serverUrl) { mutableStateOf(config.serverUrl) }
    var deviceName by remember(config.deviceName) { mutableStateOf(config.deviceName) }
    var otpPatterns by remember(config.otpPatterns) { mutableStateOf(config.otpPatterns.joinToString("\n")) }
    var notificationPackages by remember(config.notificationPackages) {
        mutableStateOf(config.notificationPackages.joinToString(","))
    }
    var certificatePins by remember(config.certificatePins) {
        mutableStateOf(config.certificatePins.joinToString("\n"))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(serverUrl, { serverUrl = it }, label = { Text("Server URL") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(deviceName, { deviceName = it }, label = { Text("Device Name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                otpPatterns, { otpPatterns = it },
                label = { Text("OTP Regex Patterns (one per line)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )
            OutlinedTextField(
                notificationPackages, { notificationPackages = it },
                label = { Text("Notification package filter (comma-separated, empty = all)") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                certificatePins, { certificatePins = it },
                label = { Text("Certificate pins (sha256/…, one per line)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
            Button(
                onClick = {
                    viewModel.save(serverUrl, deviceName, otpPatterns, notificationPackages, certificatePins)
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save & Restart Agent") }

            if (saved) Text("Saved", color = MaterialTheme.colorScheme.primary)

            Button(
                onClick = { viewModel.resetRegistration() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Reset Registration") }
        }
    }
}

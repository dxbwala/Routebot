package com.routedns.routebot.domain.usecase

import com.routedns.routebot.common.Result
import com.routedns.routebot.domain.model.DeviceRegistrationRequest
import com.routedns.routebot.domain.model.DeviceRegistrationResponse
import com.routedns.routebot.domain.model.LoginRequest
import com.routedns.routebot.domain.model.OtpEvent
import com.routedns.routebot.domain.model.OtpSource
import com.routedns.routebot.domain.model.TokenPair
import com.routedns.routebot.domain.repository.AuthRepository
import com.routedns.routebot.domain.repository.SecureStorageRepository
import javax.inject.Inject

class LoginUseCase @Inject constructor(
    private val authRepository: AuthRepository,
    private val secureStorage: SecureStorageRepository
) {
    suspend operator fun invoke(email: String, password: String): Result<TokenPair> {
        val result = authRepository.login(LoginRequest(email.trim(), password))
        result.onSuccess { secureStorage.saveJwtAccess(it.accessToken) }
        return result
    }
}

class RegisterDeviceUseCase @Inject constructor(
    private val authRepository: AuthRepository,
    private val secureStorage: SecureStorageRepository
) {
    suspend operator fun invoke(
        accessToken: String,
        request: DeviceRegistrationRequest
    ): Result<DeviceRegistrationResponse> {
        val result = authRepository.registerDevice(accessToken, request)
        result.onSuccess { response ->
            secureStorage.saveApiKey(response.apiKey)
            secureStorage.saveDeviceId(response.device.id)
            secureStorage.saveDeviceUuid(response.device.deviceUuid)
            secureStorage.saveDeviceName(response.device.name)
        }
        return result
    }
}

class RegisterWithApiKeyUseCase @Inject constructor(
    private val authRepository: AuthRepository,
    private val secureStorage: SecureStorageRepository
) {
    suspend operator fun invoke(apiKey: String, serverUrl: String): Result<Unit> {
        secureStorage.saveApiKey(apiKey.trim())
        secureStorage.saveServerUrl(serverUrl.trim().trimEnd('/'))
        return authRepository.registerWithApiKey(apiKey.trim(), serverUrl.trim())
    }
}

class ExtractOtpUseCase @Inject constructor(
    private val secureStorage: SecureStorageRepository
) {
    suspend operator fun invoke(sender: String, rawText: String, source: OtpSource): OtpEvent? {
        val patterns = secureStorage.getOtpPatterns()
        for (pattern in patterns) {
            val regex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull() ?: continue
            val match = regex.find(rawText) ?: continue
            val code = match.groupValues.getOrNull(1) ?: match.value
            if (code.isNotBlank()) {
                return OtpEvent(
                    source = source,
                    sender = sender,
                    otpCode = code.trim(),
                    rawText = rawText,
                    pattern = pattern
                )
            }
        }
        return null
    }
}

class IsDeviceRegisteredUseCase @Inject constructor(
    private val secureStorage: SecureStorageRepository
) {
    suspend operator fun invoke(): Boolean = secureStorage.isRegistered()
}

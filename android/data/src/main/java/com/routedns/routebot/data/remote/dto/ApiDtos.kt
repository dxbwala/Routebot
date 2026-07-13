package com.routedns.routebot.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: ApiError? = null
)

@Serializable
data class ApiError(
    val code: String,
    val message: String
)

@Serializable
data class AuthData(
    val tokens: com.routedns.routebot.domain.model.TokenPair
)

@Serializable
data class DeviceRegisterData(
    val device: com.routedns.routebot.domain.model.Device,
    @SerialName("api_key") val apiKey: String
)

@Serializable
data class EmptyData(val ok: Boolean = true)

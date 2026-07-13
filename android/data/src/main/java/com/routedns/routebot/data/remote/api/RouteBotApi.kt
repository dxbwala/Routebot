package com.routedns.routebot.data.remote.api

import com.routedns.routebot.data.remote.dto.ApiResponse
import com.routedns.routebot.data.remote.dto.AuthData
import com.routedns.routebot.data.remote.dto.DeviceRegisterData
import com.routedns.routebot.domain.model.CallEvent
import com.routedns.routebot.domain.model.CommandAckRequest
import com.routedns.routebot.domain.model.DeviceHeartbeat
import com.routedns.routebot.domain.model.DeviceRegistrationRequest
import com.routedns.routebot.domain.model.LoginRequest
import com.routedns.routebot.domain.model.NotificationEvent
import com.routedns.routebot.domain.model.OtpEvent
import com.routedns.routebot.domain.model.SmsMessage
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path

interface RouteBotApi {
    @POST("api/v1/auth/login")
    suspend fun login(@Body body: LoginRequest): ApiResponse<AuthData>

    @POST("api/v1/devices")
    suspend fun registerDevice(
        @Header("Authorization") authorization: String,
        @Body body: DeviceRegistrationRequest
    ): ApiResponse<DeviceRegisterData>

    @POST("api/v1/enrollment/claim")
    suspend fun claimEnrollment(
        @Body body: com.routedns.routebot.domain.model.EnrollmentClaimRequest
    ): ApiResponse<DeviceRegisterData>

    @POST("api/v1/agent/heartbeat")
    suspend fun heartbeat(
        @Header("X-Device-API-Key") apiKey: String,
        @Body body: DeviceHeartbeat
    ): ApiResponse<Map<String, DeviceHeartbeat>>

    @POST("api/v1/agent/sms")
    suspend fun ingestSms(
        @Header("X-Device-API-Key") apiKey: String,
        @Body body: SmsMessage
    ): ApiResponse<Map<String, SmsMessage>>

    @POST("api/v1/agent/sms/{id}/status")
    suspend fun updateSmsStatus(
        @Header("X-Device-API-Key") apiKey: String,
        @Path("id") smsId: String,
        @Body body: com.routedns.routebot.domain.model.SmsStatusUpdateRequest
    ): ApiResponse<Map<String, Boolean>>

    @POST("api/v1/agent/crash")
    suspend fun reportCrash(
        @Header("X-Device-API-Key") apiKey: String,
        @Body body: com.routedns.routebot.domain.model.CrashReport
    ): ApiResponse<Map<String, Boolean>>

    @POST("api/v1/agent/otp")
    suspend fun ingestOtp(
        @Header("X-Device-API-Key") apiKey: String,
        @Body body: OtpEvent
    ): ApiResponse<Map<String, OtpEvent>>

    @POST("api/v1/agent/notifications")
    suspend fun ingestNotification(
        @Header("X-Device-API-Key") apiKey: String,
        @Body body: NotificationEvent
    ): ApiResponse<Map<String, NotificationEvent>>

    @POST("api/v1/agent/calls")
    suspend fun ingestCall(
        @Header("X-Device-API-Key") apiKey: String,
        @Body body: CallEvent
    ): ApiResponse<Map<String, CallEvent>>

    @POST("api/v1/agent/commands/{id}/ack")
    suspend fun ackCommand(
        @Header("X-Device-API-Key") apiKey: String,
        @Path("id") commandId: String,
        @Body body: CommandAckRequest
    ): ApiResponse<Map<String, Boolean>>

    @Multipart
    @POST("api/v1/agent/media")
    suspend fun uploadMedia(
        @Header("X-Device-API-Key") apiKey: String,
        @Part("media_type") mediaType: RequestBody,
        @Part("command_id") commandId: RequestBody?,
        @Part file: MultipartBody.Part
    ): ApiResponse<Map<String, Any>>
}

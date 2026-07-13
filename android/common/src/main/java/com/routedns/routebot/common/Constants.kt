package com.routedns.routebot.common

object Constants {
    const val APP_NAME = "RouteBot"
    const val PACKAGE_NAME = "com.routedns.routebot"

    const val HEARTBEAT_INTERVAL_MS = 30_000L
    const val WS_INITIAL_BACKOFF_MS = 1_000L
    const val WS_MAX_BACKOFF_MS = 60_000L
    const val WS_PING_INTERVAL_MS = 30_000L

    const val HEALTH_SYNC_WORK_NAME = "routebot_health_sync"
    const val HEALTH_SYNC_INTERVAL_MINUTES = 15L

    const val AGENT_CHANNEL_ID = "routebot_agent"
    const val AGENT_NOTIFICATION_ID = 1001
    const val SCREEN_CAPTURE_NOTIFICATION_ID = 1002

    const val HEADER_DEVICE_API_KEY = "X-Device-API-Key"
    const val HEADER_REQUEST_ID = "X-Request-ID"
    const val HEADER_TIMESTAMP = "X-Timestamp"
    const val HEADER_SIGNATURE = "X-Signature"

    const val PREFS_SECURE_NAME = "routebot_secure_prefs"
    const val KEY_SERVER_URL = "server_url"
    const val KEY_API_KEY = "api_key"
    const val KEY_DEVICE_ID = "device_id"
    const val KEY_DEVICE_UUID = "device_uuid"
    const val KEY_DEVICE_NAME = "device_name"
    const val KEY_JWT_ACCESS = "jwt_access"
    const val KEY_OTP_PATTERNS = "otp_patterns"
    const val KEY_NOTIFICATION_PACKAGES = "notification_packages"
    const val KEY_CERTIFICATE_PINS = "certificate_pins"

    val DEFAULT_OTP_PATTERNS = listOf(
        """\b(\d{4,8})\b""",
        """(?:code|otp|pin)[:\s]+(\d{4,8})""",
        """(?:verification|verify)[:\s]+(\d{4,8})"""
    )
}

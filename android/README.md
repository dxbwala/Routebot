# RouteBot Android Agent

Production-ready multi-module Android device agent for [RouteDNS SaaS](../docs/AGENT.md).

## Modules

| Module | Purpose |
|--------|---------|
| `:app` | Compose UI, foreground service, receivers, WorkManager |
| `:common` | Constants, `Result`, structured logging |
| `:domain` | Models, repository contracts, use cases |
| `:data` | Room offline queue, Retrofit/OkHttp, Keystore storage, WebSocket client |
| `:core` | Device health collectors (battery, network, storage, RAM) |

## Architecture

- **Clean Architecture** with MVVM presentation layer
- **Hilt** dependency injection
- **Jetpack Compose** UI (Setup, Dashboard, Settings)
- **Foreground service** for persistent WebSocket + heartbeat
- **WorkManager** periodic health sync (15 min)

## Registration

Two paths on the Setup screen:

1. **Owner credentials** — login via JWT (`POST /api/v1/auth/login`), then register device (`POST /api/v1/devices`). API key returned and stored securely.
2. **Dashboard API key** — paste a pre-provisioned device API key and server URL.

Credentials are stored with **Android Keystore + EncryptedSharedPreferences**.

## Agent runtime

- WebSocket: `{server}/ws/agent?api_key=...`
- REST heartbeat: `POST /api/v1/agent/heartbeat` every 30s
- Offline events queued in Room and flushed on reconnect
- Remote commands handled via WebSocket `command` messages

## Build

```bash
cd android
./gradlew assembleDebug
```

Requires Android SDK 35. Gradle wrapper properties are included; run `gradle wrapper` if `gradlew` is not yet generated.

## Permissions

Grant runtime permissions from Dashboard. Enable **Notification Access** manually in system settings for the notification gateway.

## USSD limitations

USSD uses `TelephonyManager.sendUssdRequest` (API 26+). Support varies by carrier and OEM; multi-step USSD menus are not supported. See `UssdHelper` KDoc for full details.

## Certificate pinning

Optional pins can be configured in Settings (one `sha256/…` pin per line). When empty, standard system TLS trust anchors are used.

## Security notes

- No secrets in logs (`RouteBotLog` redacts sensitive field names)
- HTTPS only (`network_security_config` disables cleartext)
- Local media files are AES-GCM encrypted before upload, then deleted

## Known limitations

- Screenshot capture requires a `MediaProjection` grant (not available headlessly from remote command alone)
- Video recording from background may fail on Android 10+ without foreground camera service
- CPU usage collection is not implemented (backend field optional)
- `gradlew` script not included — generate with `gradle wrapper` or Android Studio

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

USSD tries `TelephonyManager.sendUssdRequest` first, then dials + Accessibility scrape/type for
OEM quirks and multi-step menus. Enable **RouteBot USSD Capture** in Accessibility on Oppo/ColorOS.

Dual-SIM: `sim_slot` **1** = SIM 1, **2** = SIM 2; omit to use Dial/default voice SIM.
See [`docs/architecture/sim-slots.md`](../docs/architecture/sim-slots.md) and
[`docs/architecture/ussd-limitations.md`](../docs/architecture/ussd-limitations.md).

## Certificate pinning

Optional pins can be configured in Settings (one `sha256/…` pin per line). When empty, standard system TLS trust anchors are used.

## Security notes

- No secrets in logs (`RouteBotLog` redacts sensitive field names)
- HTTPS only (`network_security_config` disables cleartext)

## Known limitations

- CPU usage collection is best-effort / may be unavailable on some OEMs
- Dual-SIM tray numbering is **1-based** (`sim_slot` / `slotIndex`: 1 = SIM 1, 2 = SIM 2)
- `gradlew` script not included — generate with `gradle wrapper` or Android Studio

You are a Senior Android Architect and Senior Go Backend Engineer.

Design and build a production-ready Android Device Agent named "RouteBot" for the RouteDNS SaaS platform.

Tech Stack

Backend
- Go 1.26.5
- Fiber v3 latest
- PostgreSQL
- Redis/Valkey
- WebSocket
- JWT Authentication
- REST API
- Docker
- Clean Architecture
- Hexagonal Architecture
- Repository Pattern

Android
- Kotlin
- Jetpack Compose
- MVVM
- WorkManager
- Foreground Service
- Room Database
- OkHttp WebSocket
- Android Keystore
- Hilt DI
- Coroutines
- Flow

Requirements

1. Device Registration
- Secure device registration
- Device UUID
- API Key
- JWT
- Heartbeat
- Online/Offline status

2. Device Monitor
- Battery
- Charging
- Storage
- RAM
- CPU
- Network
- WiFi
- Mobile Data
- Signal Strength
- SIM Information
- Android Version
- Manufacturer
- Model
- App Version

3. Health Check
- Send heartbeat every 30 seconds
- Report device status
- Auto reconnect WebSocket
- Offline queue
- Retry policy

4. SMS Gateway
- Send SMS
- Receive SMS
- Delivery report (where supported)
- Multi SIM support
- Webhook forwarding

5. OTP Relay
- Detect OTP from SMS
- Detect OTP from notifications where Android permits
- Regex extraction
- Secure webhook delivery

6. Notification Gateway
- Notification Listener Service
- App filtering
- JSON forwarding
- Webhook support

7. USSD Gateway
- Initiate USSD requests using supported Android APIs
- Capture responses only where officially supported by the device/OS
- Clearly document device and Android version limitations
- Do not rely on unsupported or hidden APIs

8. Call Monitor
- Incoming call
- Outgoing call
- Missed call
- Call state
- Call log synchronization where permitted

9. Remote Command
- Execute predefined server commands
- Config update
- Ping
- Restart internal services
- Sync data
- Clear cache
- Update configuration

10. Webhook Trigger
- Event based webhook
- Retry
- Signature verification
- HMAC authentication

11. Security
- Android Keystore
- TLS
- JWT
- Device API Keys
- Replay protection
- Request signing
- Local database encryption
- Certificate pinning

12. Logging
- Structured logs
- Error reporting
- Crash reporting
- Audit logs

13. Dashboard APIs
- Register
- Login
- Device list
- Live status
- SMS history
- Notification history
- OTP history
- Command queue
- Health metrics








. Documentation
Generate:
- Complete folder structure
- Database schema
- REST API specification
- WebSocket protocol
- Sequence diagrams
- Deployment guide
- Docker Compose
- CI/CD (GitHub Actions) for bulding apk
- Unit tests
- Integration tests


Code Quality

- Production ready
- Modular
- SOLID
- Clean Architecture
- 100% typed
- Fully documented
- Enterprise grade
- Secure by default

# Database schema

Source of truth: [`backend/migrations/`](../../backend/migrations/) (`001_init.sql`,
`002_request_signing.sql`, `003_media_logs.sql`). Migrations run automatically on API startup
and are idempotent (safe to re-run).

## Tables

| Table | Purpose |
|-------|---------|
| `users` | Dashboard accounts |
| `refresh_tokens` | Refresh token hashes |
| `devices` | Registered agents + API key hash + encrypted API key copy (for request signing) |
| `device_heartbeats` | Telemetry samples (battery, storage, RAM, CPU, network, SIM, ...) |
| `sms_messages` | Inbound/outbound SMS + delivery status |
| `otp_events` | Extracted OTPs |
| `notification_events` | Notification gateway |
| `call_events` | Call monitor |
| `commands` | Remote command queue |
| `media_uploads` | Audio/video/screenshot/logs metadata |
| `webhook_endpoints` | Customer webhook config |
| `webhook_deliveries` | Delivery attempts + idempotency |
| `audit_logs` | Security/audit trail (includes device registration, commands, crashes) |

### `devices.api_key_enc`

An AES-256-GCM encrypted copy of the device's raw API key, encrypted with the server-side
`REQUEST_SIGNING_KEY` (distinct from `DEVICE_API_KEY_PEPPER`, which only produces a one-way
hash used for authentication lookup). This lets the server independently recompute the HMAC
signature the device sends on every agent request — see [rest.md](../api/rest.md).

## Device status

- `online` — recent heartbeat / WS connected
- `offline` — stale beyond `OFFLINE_THRESHOLD_SECONDS`
- `disabled` — manually blocked

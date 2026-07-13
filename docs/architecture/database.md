# Database schema

Source of truth: [`backend/migrations/001_init.sql`](../../backend/migrations/001_init.sql)

## Tables

| Table | Purpose |
|-------|---------|
| `users` | Dashboard accounts |
| `refresh_tokens` | Refresh token hashes |
| `devices` | Registered agents + API key hash |
| `device_heartbeats` | Telemetry samples |
| `sms_messages` | Inbound/outbound SMS |
| `otp_events` | Extracted OTPs |
| `notification_events` | Notification gateway |
| `call_events` | Call monitor |
| `commands` | Remote command queue |
| `media_uploads` | Audio/video/screenshot metadata |
| `webhook_endpoints` | Customer webhook config |
| `webhook_deliveries` | Delivery attempts + idempotency |
| `audit_logs` | Security/audit trail |

## Device status

- `online` — recent heartbeat / WS connected
- `offline` — stale beyond `OFFLINE_THRESHOLD_SECONDS`
- `disabled` — manually blocked

# REST API

Base URL: `/api/v1`  
Envelope:

```json
{ "success": true, "data": {} }
{ "success": false, "error": { "code": "bad_request", "message": "..." } }
```

## Auth (public, rate-limited)

| Method | Path | Body |
|--------|------|------|
| POST | `/auth/register` | `{ email, password, display_name }` |
| POST | `/auth/login` | `{ email, password }` |
| POST | `/auth/refresh` | `{ refresh_token }` |

Returns `{ user, tokens: { access_token, refresh_token, expires_at } }`.

Dashboard requests: `Authorization: Bearer <access_token>`.

## Dashboard (JWT)

| Method | Path | Description |
|--------|------|-------------|
| POST | `/devices` | Register device; response includes one-time `api_key` |
| GET | `/devices` | List devices |
| GET | `/devices/:id` | Device detail |
| GET | `/devices/:id/health` | Latest heartbeat |
| GET | `/devices/:id/health/history` | Heartbeat history (up to 200 samples) |
| GET | `/devices/:id/live` | Server-Sent Events stream of live status/heartbeat pushes |
| GET | `/devices/:id/sms` | SMS history |
| GET | `/devices/:id/otp` | OTP history |
| GET | `/devices/:id/notifications` | Notification history |
| GET | `/devices/:id/calls` | Call history |
| POST | `/devices/:id/commands` | Queue command `{ command_type, payload }` |
| GET | `/devices/:id/commands` | Command queue/history |
| GET | `/devices/:id/media` | Log-upload metadata |
| GET | `/media/:id` | Download uploaded log file |
| POST | `/webhooks` | Create webhook endpoint |
| GET | `/webhooks` | List webhook endpoints |

### Device registration body

```json
{
  "device_uuid": "stable-android-id",
  "name": "Warehouse Phone 1",
  "manufacturer": "Google",
  "model": "Pixel 8",
  "android_version": "14",
  "app_version": "1.0.0",
  "metadata": {}
}
```

### Command types

`ping`, `sync`, `restart_services`, `refresh_config`, `clear_cache`, `upload_logs`, `update_config`, `send_sms`, `ussd`

Dual-SIM tray numbering (`sim_slot` / heartbeat `slotIndex`): **`1` = SIM 1, `2` = SIM 2**.  
See [sim-slots.md](../architecture/sim-slots.md).

### `send_sms` payload

```json
{ "address": "+15551234567", "body": "hello", "sim_slot": 1 }
```

`sim_slot` is **1-based** physical tray: `1` = SIM 1, `2` = SIM 2 (default `1`).

### `ussd` payload

```json
{ "code": "*123#", "sim_slot": 1, "steps": ["1"] }
```

`sim_slot` is **1-based** like SMS: `1` = SIM 1, `2` = SIM 2.  
Omit `sim_slot` to use the device **Dial / default voice SIM**.

Optional override: `subscription_id` (Android subscription id from heartbeat `sim_info`) wins if both are set.

USSD platform limits: [ussd-limitations.md](../architecture/ussd-limitations.md).

## Agent (device API key + request signing)

Every agent request must carry:

| Header | Description |
|--------|-------------|
| `X-Device-API-Key` | `rb_...` issued at registration/enrollment |
| `X-Timestamp` | Unix seconds, must be within `REQUEST_SIGNATURE_MAX_SKEW` (default 5m) of server time |
| `X-Signature` | `hex(HMAC-SHA256(secret = raw_api_key, message = "<timestamp>." + body))` |
| `X-Request-ID` | Unique per request; reused values are rejected as replays within the skew window |

The server verifies the signature by decrypting its own stored copy of the device's API key
(kept separately from the one-way hash used to authenticate `X-Device-API-Key`), so both sides
can independently compute the same HMAC. This provides both **request signing** and **replay
protection** (PRD §11) without any extra round trip. Requests missing or failing these checks
receive `401 unauthorized`.

| Method | Path | Description |
|--------|------|-------------|
| POST | `/agent/heartbeat` | Telemetry sample |
| POST | `/agent/crash` | Crash report `{ message, stack_trace, app_version }` |
| POST | `/agent/sms` | Ingest SMS; response includes the created row's `id` |
| POST | `/agent/sms/:id/status` | Delivery report `{ status: sent\|failed\|delivered\|delivery_failed, delivered_at? }` |
| POST | `/agent/otp` | Ingest OTP |
| POST | `/agent/notifications` | Ingest notification |
| POST | `/agent/calls` | Ingest call event |
| POST | `/agent/commands/:id/ack` | Ack/complete command |
| POST | `/agent/media` | Multipart upload for agent logs only (`media_type`: `logs`, `file`, optional `command_id`) |

### Heartbeat body (partial)

```json
{
  "battery_level": 80,
  "is_charging": true,
  "storage_free_mb": 12000,
  "ram_free_mb": 2048,
  "cpu_usage": 12.5,
  "network_type": "wifi",
  "wifi_ssid": "Office",
  "signal_strength": -60,
  "sim_info": [{"slotIndex": 1, "subscriptionId": 6, "carrierName": "Carrier", "displayName": "SIM 1", "phoneNumber": "+15551234567"}],
  "payload": {"manufacturer": "Google", "model": "Pixel 8"}
}
```

`cpu_usage` is best-effort (derived from `/proc/stat` deltas) and may be `null` on devices/OS
versions that restrict access. `sim_info.slotIndex` is **1-based** (`1` = SIM 1, `2` = SIM 2).
`phoneNumber` is filled from telephony when available, otherwise via a rate-limited USSD `*2#`
discovery — see [sim-slots.md](../architecture/sim-slots.md).

### Agent SMS ingest body (partial)

```json
{
  "direction": "inbound",
  "address": "+15551234567",
  "body": "OTP 123456",
  "sim_slot": 1,
  "status": "received"
}
```

`sim_slot` on stored SMS rows is **1-based** (`1` / `2`).

## Health

`GET /healthz` → `{ "success": true, "data": { "status": "ok" } }`

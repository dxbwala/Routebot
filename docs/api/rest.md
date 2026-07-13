# REST API

Base URL: `/api/v1`  
Envelope:

```json
{ "success": true, "data": {} }
{ "success": false, "error": { "code": "bad_request", "message": "..." } }
```

## Auth (public)

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
| GET | `/devices/:id/sms` | SMS history |
| GET | `/devices/:id/otp` | OTP history |
| GET | `/devices/:id/notifications` | Notification history |
| GET | `/devices/:id/calls` | Call history |
| POST | `/devices/:id/commands` | Queue command `{ command_type, payload }` |
| GET | `/devices/:id/commands` | Command queue/history |
| GET | `/devices/:id/media` | Media metadata |
| GET | `/media/:id` | Download media bytes |
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

`ping`, `sync`, `restart_services`, `refresh_config`, `clear_cache`, `upload_logs`, `update_config`, `record_audio`, `record_video`, `take_screenshot`, `send_sms`, `ussd`

## Agent (device API key)

Header: `X-Device-API-Key: rb_...`

| Method | Path | Description |
|--------|------|-------------|
| POST | `/agent/heartbeat` | Telemetry sample |
| POST | `/agent/sms` | Ingest SMS |
| POST | `/agent/otp` | Ingest OTP |
| POST | `/agent/notifications` | Ingest notification |
| POST | `/agent/calls` | Ingest call event |
| POST | `/agent/commands/:id/ack` | Ack/complete command |
| POST | `/agent/media` | Multipart upload (`media_type`, `file`, optional `command_id`) |

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
  "sim_info": [],
  "payload": {}
}
```

## Health

`GET /healthz` → `{ "success": true, "data": { "status": "ok" } }`

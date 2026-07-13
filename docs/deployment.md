# Deployment guide

## Prerequisites

- Docker + Docker Compose
- Go 1.26+ (local API development)
- Android Studio / SDK 35 (agent builds)

## Environment

Copy [`.env.example`](../.env.example) to `.env` and rotate all secrets:

- `JWT_SECRET` (≥ 32 chars)
- `DEVICE_API_KEY_PEPPER`
- `WEBHOOK_HMAC_SECRET`

## Compose stack

```bash
docker compose up --build -d
```

Public entry is **nginx** (ports 80/443). The API listens only on the Docker network.

| Service | Port | Image |
|---------|------|-------|
| `nginx` | 80, 443 | nginx:1.27-alpine |
| `api` | 8080 (internal) | local Dockerfile |
| `postgres` | 5432 | postgres:16-alpine |
| `redis` | 6379 | valkey/valkey:8-alpine |

Config: [`deploy/nginx/`](../deploy/nginx/)

- Proxies `/` and `/api/` to the Fiber API
- Upgrades `/ws/` for agent WebSockets (long timeouts)
- `client_max_body_size 64m` for media uploads
- Auto-generates a self-signed TLS cert on first start (replace for production)

```bash
curl -s http://localhost/healthz
curl -sk https://localhost/healthz
```

To force HTTP→HTTPS, uncomment the `return 301` block in [`deploy/nginx/nginx.conf`](../deploy/nginx/nginx.conf).

Migrations run on API startup from `MIGRATIONS_DIR` (also applied via Postgres init for fresh volumes).

## Local API without Docker API / nginx

```bash
docker compose up -d postgres redis
cd backend
export $(grep -v '^#' ../.env | xargs)
MIGRATIONS_DIR=./migrations go run ./cmd/api
```

Point the agent at `http://localhost:8080` in that mode (API exposed directly).

## Media storage

Files land under `MEDIA_STORAGE_PATH` (Compose volume `/data/media`). Soft-delete is tracked in `media_uploads.deleted_at`.

## Production checklist

- [ ] Replace nginx self-signed certs with a real certificate (Let's Encrypt / volume mount into `/etc/nginx/certs`)
- [ ] Enable HTTP→HTTPS redirect in `nginx.conf`
- [ ] Rotate secrets; never commit `.env`
- [ ] Restrict `CORS_ORIGINS`
- [ ] Enable certificate pinning pins in the Android agent settings
- [ ] Back up Postgres; retain Valkey as ephemeral presence cache
- [ ] Configure webhook HTTPS endpoints only
- [ ] Ship Android release builds via CI (see GitHub Actions)

## Android CI APK

Workflow [`.github/workflows/android-apk.yml`](../.github/workflows/android-apk.yml) builds a debug APK on pushes that touch `android/`.

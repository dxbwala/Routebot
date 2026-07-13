# Deployment guide

## Prerequisites

- Docker + Docker Compose
- Go 1.26+ (local API development)
- Android Studio / SDK 35 (agent builds)
- A real TLS certificate + key for production (see [TLS](#tls))

## Environment

Copy [`.env.example`](../.env.example) to `.env` and generate real secrets — never reuse the placeholders:

```bash
cp .env.example .env
sed -i '' "s#^JWT_SECRET=.*#JWT_SECRET=$(openssl rand -base64 48 | tr -d '\n')#" .env
sed -i '' "s#^DEVICE_API_KEY_PEPPER=.*#DEVICE_API_KEY_PEPPER=$(openssl rand -hex 32)#" .env
sed -i '' "s#^WEBHOOK_HMAC_SECRET=.*#WEBHOOK_HMAC_SECRET=$(openssl rand -hex 32)#" .env
sed -i '' "s#^POSTGRES_PASSWORD=.*#POSTGRES_PASSWORD=$(openssl rand -base64 24 | tr -dc 'A-Za-z0-9')#" .env
sed -i '' "s#^REDIS_PASSWORD=.*#REDIS_PASSWORD=$(openssl rand -base64 24 | tr -dc 'A-Za-z0-9')#" .env
chmod 600 .env
```

For production also set:

- `APP_ENV=production`
- `APP_BASE_URL=https://<your-domain>`
- `CORS_ORIGINS=https://<your-domain>` (never `*` in production)
- `POSTGRES_HOST=postgres`, `REDIS_ADDR=redis:6379` (Compose service names)

`.env` is gitignored and must never be committed. If you change `POSTGRES_PASSWORD` after the `pgdata` volume already exists, either update the password inside Postgres (`ALTER USER routebot WITH PASSWORD '...'`) or drop the volume (`docker volume rm routebot_pgdata`) — Postgres only applies `POSTGRES_PASSWORD` on first initialization of an empty data directory.

## TLS

Place your real certificate and private key at:

```
<repo-root>/certs/fullchain.pem
<repo-root>/certs/privkey.pem
```

This directory is bind-mounted read-only into the nginx container and is gitignored (`/certs/`, `*.pem`) — nothing here is ever committed. On startup, [`deploy/nginx/docker-entrypoint.sh`](../deploy/nginx/docker-entrypoint.sh) uses these files automatically; if they're missing it falls back to generating a self-signed certificate (dev-only).

Update `server_name` in [`deploy/nginx/nginx.conf`](../deploy/nginx/nginx.conf) to match your certificate's domain (defaults to `routedns.io`).

## Compose stack

```bash
docker compose up --build -d
```

Public entry is **nginx** (ports 80/443). Postgres, Redis, and the API are internal-only — no database ports are published to the host.

| Service | Exposure | Image |
|---------|----------|-------|
| `nginx` | `80`, `443` (public) | built from `deploy/nginx/Dockerfile` |
| `api` | internal only | built from `backend/Dockerfile` |
| `postgres` | internal only, password-protected | postgres:16-alpine |
| `redis` | internal only, password-protected (`requirepass`) | valkey/valkey:8-alpine |

nginx behavior ([`deploy/nginx/nginx.conf`](../deploy/nginx/nginx.conf)):

- Port 80 redirects everything to HTTPS except `/healthz`
- Port 443 terminates TLS with the certs above, sets HSTS + standard security headers
- Rate-limits `/api/v1/auth/*` and `/api/v1/enrollment/*` (10 req/min per IP, burst 5) — returns `429`
- Upgrades `/ws/` for agent WebSockets with extended timeouts
- `client_max_body_size 64m` for media uploads

The API also enforces its own per-IP rate limit on auth/enrollment endpoints as defense in depth in case it's ever reached directly.

```bash
curl -s http://localhost/healthz
curl -sk https://localhost/healthz          # or --resolve <domain>:443:127.0.0.1 to test SNI
```

Migrations run on API startup from `MIGRATIONS_DIR` (also applied via Postgres init for fresh volumes).

## Local API without Docker API / nginx

```bash
docker compose up -d postgres redis
cd backend
export $(grep -v '^#' ../.env | xargs)
MIGRATIONS_DIR=./migrations go run ./cmd/api
```

Point the agent at `http://localhost:8080` in that mode (API exposed directly). Note: this bypasses nginx's TLS termination and rate limiting — use only for local development.

## Media storage

Files land under `MEDIA_STORAGE_PATH` (Compose volume `/data/media`). Soft-delete is tracked in `media_uploads.deleted_at`.

## Android release builds

- `android/app/src/main/res/xml/network_security_config.xml` is the **release** default: cleartext HTTP is disabled, only system trust anchors are used.
- `android/app/src/debug/res/xml/network_security_config.xml` overrides this for **debug** builds only, allowing cleartext to a local/LAN API for development. This override is never included in a release build (verified by inspecting `merged_res/release`).
- Certificate pinning: configure pins via the Settings screen (stored via `SecureStorageRepository.saveCertificatePins`); leave empty to rely on standard CA trust.
- Release builds are not yet signed with a production keystore — set up Play App Signing or a dedicated release keystore before publishing.

## Production checklist

- [x] Real TLS certificate mounted from `certs/*.pem` (not self-signed)
- [x] HTTP→HTTPS redirect enabled
- [x] HSTS + security headers set
- [x] Secrets rotated (JWT, device API key pepper, webhook HMAC, Postgres, Redis)
- [x] `CORS_ORIGINS` restricted to the real domain
- [x] Postgres/Redis not published on host ports; Redis requires a password
- [x] Rate limiting on auth/enrollment endpoints (nginx + app level)
- [x] Android release builds disable cleartext traffic
- [ ] Configure certificate pinning pins for your domain in the Android agent settings
- [ ] Back up Postgres; retain Valkey as ephemeral presence cache
- [ ] Configure webhook endpoints as HTTPS only
- [ ] Sign Android release builds with a production keystore
- [ ] Add structured metrics/tracing and log shipping
- [ ] Expand automated test coverage (HTTP handlers, WS hub, Postgres repos)

## Android CI APK

Workflow [`.github/workflows/android-apk.yml`](../.github/workflows/android-apk.yml) builds a debug APK on pushes that touch `android/`.

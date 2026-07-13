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
```

Set a dashboard admin (created/updated automatically when the API starts):

```bash
ADMIN_EMAIL=admin@routedns.io
ADMIN_PASSWORD=ChangeMeAdmin123!
ADMIN_DISPLAY_NAME=Admin
```

Then rotate the other secrets (`JWT_SECRET`, peppers, DB/Redis passwords, etc.).
sed -i '' "s#^JWT_SECRET=.*#JWT_SECRET=$(openssl rand -base64 48 | tr -d '\n')#" .env
sed -i '' "s#^DEVICE_API_KEY_PEPPER=.*#DEVICE_API_KEY_PEPPER=$(openssl rand -hex 32)#" .env
sed -i '' "s#^WEBHOOK_HMAC_SECRET=.*#WEBHOOK_HMAC_SECRET=$(openssl rand -hex 32)#" .env
sed -i '' "s#^REQUEST_SIGNING_KEY=.*#REQUEST_SIGNING_KEY=$(openssl rand -hex 32)#" .env
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
- `client_max_body_size 64m` for log uploads

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

## Log upload storage

Files land under `MEDIA_STORAGE_PATH` (Compose volume `/data/media`). Soft-delete is tracked in `media_uploads.deleted_at`. Only `media_type=logs` is accepted (from the `upload_logs` remote command).

## Integration tests

`backend/tests/integration` exercises the real Fiber app against real Postgres + Redis (register → login → device enrollment → signed heartbeat/SMS ingest → delivery status → dashboard read-back → replay rejection). Requires the same env vars as `cmd/api` (see `.env.example`); skips gracefully if the databases aren't reachable.

```bash
cd backend
POSTGRES_HOST=localhost REDIS_ADDR=localhost:6379 go test ./tests/integration/... -v
```

CI runs this automatically with Postgres/Redis service containers — see [`.github/workflows/backend-ci.yml`](../.github/workflows/backend-ci.yml).

## Android release builds

- `android/app/src/main/res/xml/network_security_config.xml` is the **release** default: cleartext HTTP is disabled, only system trust anchors are used.
- `android/app/src/debug/res/xml/network_security_config.xml` overrides this for **debug** builds only, allowing cleartext to a local/LAN API for development. This override is never included in a release build (verified by inspecting `merged_res/release`).
- Certificate pinning: configure pins via the Settings screen (stored via `SecureStorageRepository.saveCertificatePins`); leave empty to rely on standard CA trust.
- The local Room database is encrypted at rest with SQLCipher; the passphrase is generated once and stored in Android Keystore-backed `EncryptedSharedPreferences` (`DbPassphraseProvider`), never in plaintext.
- See [platform-limitations.md](architecture/platform-limitations.md) for CPU/SIM caveats and SMS delivery report behavior.

## Android release signing

Release builds are signed from environment variables (`android/app/build.gradle.kts`), never
from a keystore committed to the repo.

### One-time setup

```bash
cd android
./scripts/generate-release-keystore.sh
```

This generates `android/release.jks` (gitignored) and prints a base64-encoded copy. Back the
`.jks` file up somewhere safe outside git — **losing it means you can never publish an update
to the same app listing again.**

Add these to the GitHub repo (Settings → Secrets and variables → Actions → New repository secret):

| Secret | Value |
|--------|-------|
| `ANDROID_RELEASE_KEYSTORE_BASE64` | base64 output from the script above |
| `ANDROID_KEYSTORE_PASSWORD` | the keystore password you entered |
| `ANDROID_KEY_ALIAS` | `routebot` (default from the script) |
| `ANDROID_KEY_PASSWORD` | the key password you entered |

### Building a signed release locally

Export the same four values as environment variables (`ROUTEBOT_KEYSTORE_PATH`,
`ROUTEBOT_KEYSTORE_PASSWORD`, `ROUTEBOT_KEY_ALIAS`, `ROUTEBOT_KEY_PASSWORD`) — never in a
committed file — then:

```bash
./gradlew :app:assembleRelease :app:bundleRelease
```

Without those variables set, `release` builds remain **unsigned** (safe for local
experimentation, not distributable).

### Publishing via CI

Workflow: [`.github/workflows/android-release.yml`](../.github/workflows/android-release.yml).

```bash
git tag android-v1.0.0
git push origin android-v1.0.0
```

CI builds **two signed phone APKs** (same codebase, `targetSdk` 35) plus a modern AAB:

| Artifact | Devices |
|----------|---------|
| `RouteBot-x.y.z-modern-arm64.apk` | Current 64-bit phones (`arm64-v8a`) — recommended |
| `RouteBot-x.y.z-legacy-armv7.apk` | Older 32-bit ARM phones (`armeabi-v7a`) |
| `RouteBot-x.y.z-modern.aab` | Play Console |

Both flavors support Android 8.0+ (`minSdk` 26) through the latest platform (`compileSdk` /
`targetSdk` 35). versionCodes are `base*10+1` (legacy) and `base*10+2` (modern) so updates
do not collide. You can also trigger the workflow manually from the Actions tab with a version
input (`workflow_dispatch`) without pushing a tag first.

## Production checklist

- [x] Real TLS certificate mounted from `certs/*.pem` (not self-signed)
- [x] HTTP→HTTPS redirect enabled
- [x] HSTS + security headers set
- [x] Secrets rotated (JWT, device API key pepper, webhook HMAC, Postgres, Redis)
- [x] `CORS_ORIGINS` restricted to the real domain
- [x] Postgres/Redis not published on host ports; Redis requires a password
- [x] Rate limiting on auth/enrollment endpoints (nginx + app level)
- [x] Android release builds disable cleartext traffic
- [x] Request signing + replay protection on all agent REST endpoints
- [x] Local Room database encrypted at rest (SQLCipher, Keystore-backed passphrase)
- [x] Crash reporting (uncaught exception handler → persisted + uploaded next launch)
- [x] SMS delivery reports (real sent/delivered callbacks, not hardcoded status)
- [x] Integration test suite (real HTTP + Postgres + Redis) running in CI
- [x] Android release builds signed with a production keystore via CI (`android-release.yml`)
- [ ] Configure certificate pinning pins for your domain in the Android agent settings
- [ ] Back up Postgres; retain Valkey as ephemeral presence cache
- [ ] Configure webhook endpoints as HTTPS only
- [ ] Add structured metrics/tracing and log shipping

## Android CI APK

Workflow [`.github/workflows/android-apk.yml`](../.github/workflows/android-apk.yml) builds a debug APK on pushes that touch `android/`.

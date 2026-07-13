# RouteBot

Enterprise Android Device Agent and backend for the RouteDNS SaaS platform.

## Components

| Path | Description |
|------|-------------|
| [`backend/`](backend/) | Go 1.26 + Fiber v3 API, WebSocket hub, webhooks |
| [`android/`](android/) | Kotlin multi-module agent (Compose, Hilt, Room, WorkManager) |
| [`docs/`](docs/) | PRD, architecture, API, WebSocket, deployment |
| [`docker-compose.yml`](docker-compose.yml) | Postgres 16 + Valkey + API |

## Quick start (API)

```bash
cp .env.example .env
docker compose up -d postgres redis
cd backend
export $(grep -v '^#' ../.env | xargs)
MIGRATIONS_DIR=./migrations go run ./cmd/api
```

Health: `GET http://localhost:8080/healthz`

## Quick start (full stack)

```bash
cp .env.example .env
docker compose up --build -d
```

Traffic enters via **nginx** on `:80` / `:443` (API is not exposed publicly).

```bash
curl -s http://localhost/healthz
open http://localhost/dashboard   # QR pairing UI
```

**Pair a phone:** open `/dashboard` → sign in → Generate QR → in RouteBot app tap Scan QR.

## Android agent

Open [`android/`](android/) in Android Studio, or:

```bash
cd android
gradle wrapper   # once
./gradlew :app:assembleDebug
```

See [`android/README.md`](android/README.md) for permissions, USSD limits, and enrollment.

## Documentation

- [PRD](docs/PRD.md)
- [Agent rules](docs/AGENT.md)
- [Architecture](docs/architecture/overview.md)
- [REST API](docs/api/rest.md)
- [WebSocket protocol](docs/api/websocket.md)
- [Database schema](docs/architecture/database.md)
- [Deployment](docs/deployment.md)

## License

Proprietary — RouteDNS.

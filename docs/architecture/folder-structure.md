# Folder structure

```text
Routebot/
├── android/                 # Kotlin multi-module agent
│   ├── app/                 # Compose UI, services, receivers, workers
│   ├── common/              # Shared constants / Result / logging
│   ├── core/                # Device telemetry collectors
│   ├── data/                # Room, networking, Keystore, repos
│   ├── domain/              # Models, ports, use cases
│   └── gradle/              # Version catalog + wrapper props
├── backend/
│   ├── cmd/api/             # Entrypoint
│   ├── internal/
│   │   ├── adapters/        # http, ws, postgres, redis, webhook
│   │   ├── config/
│   │   ├── domain/
│   │   ├── ports/
│   │   ├── pkg/             # auth, crypto, logger, response
│   │   └── service/
│   ├── migrations/
│   └── Dockerfile
├── docs/
│   ├── api/
│   ├── architecture/
│   ├── AGENT.md
│   ├── PRD.md
│   └── deployment.md
├── storage/media/
├── .github/workflows/
├── docker-compose.yml
└── .env.example
```

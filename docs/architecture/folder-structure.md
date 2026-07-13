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
│   │   ├── pkg/             # auth, crypto (HMAC + secret encryption), logger, response
│   │   └── service/
│   ├── migrations/          # 001_init, 002_request_signing, 003_media_logs
│   ├── tests/integration/   # Real HTTP + Postgres + Redis end-to-end tests
│   └── Dockerfile
├── deploy/nginx/            # nginx.conf, proxy_params.conf, Dockerfile, entrypoint
├── docs/
│   ├── api/                 # rest.md, websocket.md
│   ├── architecture/        # database, folder-structure, sequences, platform-limitations
│   ├── AGENT.md
│   ├── PRD.md
│   └── deployment.md
├── storage/media/
├── certs/                   # Real TLS cert/key (gitignored)
├── .github/workflows/
├── docker-compose.yml
└── .env.example
```

Notable Android additions beyond the original scaffold:

- `app/src/main/java/.../crash/CrashReporter.kt` — uncaught exception handler + pending-crash upload
- `app/src/debug/res/xml/network_security_config.xml` — debug-only cleartext override (release stays HTTPS-only)
- `data/src/main/java/.../data/security/DbPassphraseProvider.kt` — SQLCipher passphrase, Keystore-backed
- `data/src/test/` — offline-queue concurrency regression test

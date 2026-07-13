# RouteBot Backend

Go Fiber v3 API for device agents and dashboard clients.

## Run

```bash
cp ../.env.example ../.env
docker compose -f ../docker-compose.yml up -d postgres redis
export $(grep -v '^#' ../.env | xargs)
MIGRATIONS_DIR=./migrations go run ./cmd/api
```

## Test

```bash
go test ./...
```

## Layout

See [architecture overview](../docs/architecture/overview.md).

Dual-SIM command payloads (`sim_slot` 1|2): [sim-slots.md](../docs/architecture/sim-slots.md).

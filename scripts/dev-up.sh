#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
if [[ ! -f .env ]]; then
  cp .env.example .env
  echo "Created .env from .env.example — rotate secrets before production use."
fi
docker compose up -d postgres redis
echo "Postgres + Valkey are up. Start API with:"
echo "  cd backend && set -a && source ../.env && set +a && MIGRATIONS_DIR=./migrations go run ./cmd/api"

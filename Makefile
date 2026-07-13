# RouteBot — common developer / ops targets
#
# Usage: make <target>
# Run `make` or `make help` to list targets.

SHELL := /bin/bash
.DEFAULT_GOAL := help

COMPOSE ?= docker compose
ROOT := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
BACKEND := $(ROOT)/backend
ANDROID := $(ROOT)/android
MIGRATIONS := $(BACKEND)/migrations

POSTGRES_USER ?= routebot
POSTGRES_DB ?= routebot

.PHONY: help all env up down restart rebuild ps status logs logs-api logs-nginx \
	up-db migrate migrade db-shell redis-cli health \
	test test-backend test-integration \
	android-debug android-release \
	clean prune

help: ## Show this help
	@echo "RouteBot make targets"
	@echo ""
	@grep -E '^[a-zA-Z0-9_-]+:.*?## ' $(MAKEFILE_LIST) | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2}'
	@echo ""
	@echo "Examples:"
	@echo "  make all          # ensure .env + build/start full stack"
	@echo "  make up           # docker compose up -d --build"
	@echo "  make down         # stop stack"
	@echo "  make migrate      # apply SQL migrations to Postgres"

all: env up ## Ensure .env exists, then build and start the full stack

env: ## Create .env from .env.example if missing
	@if [[ ! -f "$(ROOT)/.env" ]]; then \
		cp "$(ROOT)/.env.example" "$(ROOT)/.env"; \
		echo "Created .env from .env.example — rotate secrets before production."; \
	else \
		echo ".env already present"; \
	fi

up: env ## Build and start all services (docker compose up -d --build)
	cd "$(ROOT)" && $(COMPOSE) up -d --build

up-db: env ## Start only Postgres + Redis/Valkey
	cd "$(ROOT)" && $(COMPOSE) up -d postgres redis

down: ## Stop all services (keep volumes)
	cd "$(ROOT)" && $(COMPOSE) down

restart: ## Restart all running services
	cd "$(ROOT)" && $(COMPOSE) restart

rebuild: ## Rebuild images and recreate containers
	cd "$(ROOT)" && $(COMPOSE) up -d --build --force-recreate

ps: ## Show compose service status
	cd "$(ROOT)" && $(COMPOSE) ps

status: ps ## Alias for ps

logs: ## Tail logs for all services
	cd "$(ROOT)" && $(COMPOSE) logs -f --tail=200

logs-api: ## Tail API logs
	cd "$(ROOT)" && $(COMPOSE) logs -f --tail=200 api

logs-nginx: ## Tail nginx logs
	cd "$(ROOT)" && $(COMPOSE) logs -f --tail=200 nginx

health: ## Hit local health endpoints (HTTP + HTTPS)
	@echo "nginx http  -> $$(curl -fsS http://127.0.0.1/healthz 2>/dev/null || echo unreachable)"
	@echo "nginx https -> $$(curl -fskS https://127.0.0.1/healthz 2>/dev/null || echo unreachable)"
	@echo "api         -> $$(cd "$(ROOT)" && $(COMPOSE) exec -T api wget -qO- http://127.0.0.1:8080/healthz 2>/dev/null || echo unreachable)"

# Migrations are idempotent SQL files applied in lexical order.
# Prefer applying via psql so you do not need a full API restart; also restart
# the API so its in-process migrate path stays in sync when the container is up.
migrate: up-db ## Apply backend/migrations/*.sql to Postgres
	@echo "Waiting for Postgres..."
	@cd "$(ROOT)" && $(COMPOSE) exec -T postgres \
		pg_isready -U "$(POSTGRES_USER)" -d "$(POSTGRES_DB)" >/dev/null
	@for f in $$(ls "$(MIGRATIONS)"/*.sql | sort); do \
		echo "Applying $$(basename "$$f")..."; \
		cd "$(ROOT)" && $(COMPOSE) exec -T postgres \
			psql -v ON_ERROR_STOP=1 -U "$(POSTGRES_USER)" -d "$(POSTGRES_DB)" < "$$f"; \
	done
	@if cd "$(ROOT)" && $(COMPOSE) ps --status running --services 2>/dev/null | grep -qx api; then \
		echo "Restarting api so startup migrate stays consistent..."; \
		cd "$(ROOT)" && $(COMPOSE) restart api; \
	fi
	@echo "Migrations applied."

# Common typo alias
migrade: migrate ## Alias for migrate

db-shell: ## Open psql in the Postgres container
	cd "$(ROOT)" && $(COMPOSE) exec postgres \
		psql -U "$(POSTGRES_USER)" -d "$(POSTGRES_DB)"

redis-cli: ## Open valkey-cli in the Redis container (auth from .env)
	@cd "$(ROOT)" && \
		PASS=$$(grep -E '^REDIS_PASSWORD=' .env 2>/dev/null | cut -d= -f2- || true); \
		if [[ -n "$$PASS" ]]; then \
			$(COMPOSE) exec redis valkey-cli -a "$$PASS"; \
		else \
			$(COMPOSE) exec redis valkey-cli; \
		fi

test: test-backend ## Run backend unit tests

test-backend: ## Run Go unit tests
	cd "$(BACKEND)" && go test ./...

test-integration: up-db ## Run backend integration tests (needs local DB/Redis ports)
	@cd "$(ROOT)" && set -a && source .env && set +a && \
		cd "$(BACKEND)" && \
		POSTGRES_HOST=127.0.0.1 REDIS_ADDR=127.0.0.1:6379 \
		go test ./tests/integration/... -count=1 -v

android-debug: ## Assemble Android debug APKs (legacy + modern)
	cd "$(ANDROID)" && ./gradlew :app:assembleLegacyDebug :app:assembleModernDebug

android-release: ## Assemble Android minified release APKs (unsigned unless keystore env set)
	cd "$(ANDROID)" && ./gradlew :app:assembleLegacyRelease :app:assembleModernRelease

clean: ## Stop stack and remove containers (volumes kept)
	cd "$(ROOT)" && $(COMPOSE) down --remove-orphans

prune: ## DANGER: stop stack and delete named volumes (wipes DB/media)
	@echo "This deletes Postgres/Redis/media volumes. Ctrl-C within 5s to abort."
	@sleep 5
	cd "$(ROOT)" && $(COMPOSE) down -v --remove-orphans

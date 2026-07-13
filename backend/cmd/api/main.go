package main

import (
	"context"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	httpapi "github.com/routedns/routebot/backend/internal/adapters/http"
	"github.com/routedns/routebot/backend/internal/adapters/postgres"
	redisadapter "github.com/routedns/routebot/backend/internal/adapters/redis"
	"github.com/routedns/routebot/backend/internal/adapters/webhook"
	"github.com/routedns/routebot/backend/internal/adapters/ws"
	"github.com/routedns/routebot/backend/internal/config"
	"github.com/routedns/routebot/backend/internal/pkg/auth"
	"github.com/routedns/routebot/backend/internal/pkg/logger"
	"github.com/routedns/routebot/backend/internal/service"
)

func main() {
	log := logger.New("routebot-api")
	cfg, err := config.Load()
	if err != nil {
		log.Error("config load failed", map[string]any{"error": err.Error()})
		os.Exit(1)
	}

	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer cancel()

	pool, err := postgres.NewPool(ctx, cfg.PostgresDSN)
	if err != nil {
		log.Error("postgres connect failed", map[string]any{"error": err.Error()})
		os.Exit(1)
	}
	defer pool.Close()

	migrationsDir := os.Getenv("MIGRATIONS_DIR")
	if migrationsDir == "" {
		migrationsDir = findMigrations()
	}
	if err := postgres.Migrate(ctx, pool, migrationsDir); err != nil {
		log.Error("migrate failed", map[string]any{"error": err.Error()})
		os.Exit(1)
	}

	rdb := redisadapter.NewClient(cfg.RedisAddr, cfg.RedisPassword, cfg.RedisDB)
	if err := rdb.Ping(ctx).Err(); err != nil {
		log.Error("redis connect failed", map[string]any{"error": err.Error()})
		os.Exit(1)
	}
	defer rdb.Close()

	userRepo := postgres.NewUserRepo(pool)
	refreshRepo := postgres.NewRefreshTokenRepo(pool)
	deviceRepo := postgres.NewDeviceRepo(pool)
	hbRepo := postgres.NewHeartbeatRepo(pool)
	smsRepo := postgres.NewSMSRepo(pool)
	otpRepo := postgres.NewOTPRepo(pool)
	notifRepo := postgres.NewNotificationRepo(pool)
	callRepo := postgres.NewCallRepo(pool)
	cmdRepo := postgres.NewCommandRepo(pool)
	mediaRepo := postgres.NewMediaRepo(pool)
	webhookRepo := postgres.NewWebhookRepo(pool)
	auditRepo := postgres.NewAuditRepo(pool)

	presence := redisadapter.NewPresence(rdb)
	enrollStore := redisadapter.NewEnrollmentStore(rdb)
	hub := ws.NewHub()
	dispatcher := webhook.NewDispatcher(webhookRepo, log)
	tokens := auth.NewManager(cfg.JWTSecret, cfg.JWTAccessTTL, cfg.JWTRefreshTTL)

	authSvc := service.NewAuthService(userRepo, refreshRepo, tokens, auditRepo, cfg.JWTRefreshTTL)
	deviceSvc := service.NewDeviceService(deviceRepo, hbRepo, presence, cfg.DeviceAPIKeyPepper, cfg.OfflineThresholdSeconds, auditRepo, dispatcher)
	eventSvc := service.NewEventService(smsRepo, otpRepo, notifRepo, callRepo, deviceRepo, dispatcher)
	cmdSvc := service.NewCommandService(cmdRepo, deviceRepo, hub, auditRepo, dispatcher)
	mediaSvc := service.NewMediaService(mediaRepo, deviceRepo, cfg.MediaStoragePath)
	webhookSvc := service.NewWebhookService(webhookRepo)
	enrollSvc := service.NewEnrollmentService(enrollStore, deviceSvc, auditRepo)

	wsHandler := ws.NewHandler(deviceSvc, cmdSvc, hub, log)

	app := httpapi.NewRouter(httpapi.Deps{
		Config:     cfg,
		Tokens:     tokens,
		Auth:       authSvc,
		Devices:    deviceSvc,
		Events:     eventSvc,
		Commands:   cmdSvc,
		Media:      mediaSvc,
		Webhooks:   webhookSvc,
		Enrollment: enrollSvc,
		WS:         wsHandler.Endpoint(),
	})

	go func() {
		ticker := time.NewTicker(30 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				n, err := deviceSvc.SweepOffline(context.Background())
				if err != nil {
					log.Error("offline sweep failed", map[string]any{"error": err.Error()})
					continue
				}
				if n > 0 {
					log.Info("marked devices offline", map[string]any{"count": n})
				}
			}
		}
	}()

	go func() {
		<-ctx.Done()
		_ = app.Shutdown()
	}()

	addr := ":" + cfg.AppPort
	log.Info("starting api", map[string]any{"addr": addr, "env": cfg.AppEnv})
	if err := app.Listen(addr); err != nil {
		log.Error("server stopped", map[string]any{"error": err.Error()})
	}
}

func findMigrations() string {
	candidates := []string{
		"migrations",
		"./migrations",
		"./backend/migrations",
		filepath.Join("..", "migrations"),
	}
	for _, c := range candidates {
		if st, err := os.Stat(c); err == nil && st.IsDir() {
			return c
		}
	}
	return "migrations"
}

// Package integration exercises the real Fiber app end to end against a real
// Postgres + Redis (no fakes), covering register -> login -> device
// enrollment -> signed agent heartbeat/SMS ingest -> dashboard read-back.
//
// Requires Postgres + Redis reachable via the same env vars main.go uses
// (see .env.example). Run with: go test ./tests/integration/...
// If the databases aren't reachable, the suite skips instead of failing so
// `go test ./...` still passes in environments without services configured.
package integration

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"testing"
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
	"github.com/gofiber/fiber/v3"
	"github.com/stretchr/testify/require"
)

func buildTestApp(t *testing.T) *fiber.App {
	t.Helper()

	// Load .env from repo root for local `go test` runs; CI sets real env vars directly.
	_, thisFile, _, _ := runtime.Caller(0)
	root := filepath.Join(filepath.Dir(thisFile), "..", "..", "..")
	_ = os.Chdir(root)

	cfg, err := config.Load()
	if err != nil {
		t.Skipf("skipping integration tests: config not available: %v", err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	pool, err := postgres.NewPool(ctx, cfg.PostgresDSN)
	if err != nil {
		t.Skipf("skipping integration tests: postgres not reachable: %v", err)
	}
	t.Cleanup(pool.Close)

	migrationsDir := filepath.Join(root, "backend", "migrations")
	if _, statErr := os.Stat(migrationsDir); statErr != nil {
		migrationsDir = filepath.Join(root, "migrations")
	}
	require.NoError(t, postgres.Migrate(ctx, pool, migrationsDir))

	rdb := redisadapter.NewClient(cfg.RedisAddr, cfg.RedisPassword, cfg.RedisDB)
	if err := rdb.Ping(ctx).Err(); err != nil {
		t.Skipf("skipping integration tests: redis not reachable: %v", err)
	}
	t.Cleanup(func() { _ = rdb.Close() })

	log := logger.New("routebot-api-test")

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
	deviceSvc := service.NewDeviceService(deviceRepo, hbRepo, presence, cfg.DeviceAPIKeyPepper, cfg.RequestSigningKey, cfg.RequestSignatureMaxSkew, cfg.OfflineThresholdSeconds, auditRepo, dispatcher)
	eventSvc := service.NewEventService(smsRepo, otpRepo, notifRepo, callRepo, deviceRepo, dispatcher)
	cmdSvc := service.NewCommandService(cmdRepo, deviceRepo, hub, auditRepo, dispatcher)
	mediaSvc := service.NewMediaService(mediaRepo, deviceRepo, t.TempDir())
	webhookSvc := service.NewWebhookService(webhookRepo)
	enrollSvc := service.NewEnrollmentService(enrollStore, deviceSvc, auditRepo)

	wsHandler := ws.NewHandler(deviceSvc, cmdSvc, hub, log)

	return httpapi.NewRouter(httpapi.Deps{
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
}

func doJSON(t *testing.T, app *fiber.App, method, path, token string, body any) (*http.Response, map[string]any) {
	t.Helper()
	var reader io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		require.NoError(t, err)
		reader = bytes.NewReader(b)
	}
	req := httptest.NewRequest(method, path, reader)
	req.Header.Set("Content-Type", "application/json")
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := app.Test(req, fiber.TestConfig{Timeout: 5 * time.Second, FailOnTimeout: true})
	require.NoError(t, err)
	defer resp.Body.Close()
	raw, err := io.ReadAll(resp.Body)
	require.NoError(t, err)
	var out map[string]any
	if len(raw) > 0 {
		require.NoError(t, json.Unmarshal(raw, &out))
	}
	return resp, out
}

func signedAgentRequest(t *testing.T, app *fiber.App, method, path, apiKey string, body []byte) (*http.Response, map[string]any) {
	t.Helper()
	req := httptest.NewRequest(method, path, bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Device-API-Key", apiKey)

	ts := time.Now().Unix()
	sig := signHMAC(apiKey, ts, body)
	req.Header.Set("X-Timestamp", strconv.FormatInt(ts, 10))
	req.Header.Set("X-Signature", sig)
	req.Header.Set("X-Request-ID", fmt.Sprintf("test-%d-%s", ts, path))

	resp, err := app.Test(req, fiber.TestConfig{Timeout: 5 * time.Second, FailOnTimeout: true})
	require.NoError(t, err)
	defer resp.Body.Close()
	raw, err := io.ReadAll(resp.Body)
	require.NoError(t, err)
	var out map[string]any
	if len(raw) > 0 {
		require.NoError(t, json.Unmarshal(raw, &out))
	}
	return resp, out
}

func signHMAC(secret string, timestamp int64, body []byte) string {
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte(strconv.FormatInt(timestamp, 10)))
	mac.Write([]byte("."))
	mac.Write(body)
	return hex.EncodeToString(mac.Sum(nil))
}

func TestFullDeviceLifecycle(t *testing.T) {
	app := buildTestApp(t)

	email := fmt.Sprintf("it-%d@routebot.test", time.Now().UnixNano())

	// 1. Register a dashboard user.
	resp, out := doJSON(t, app, http.MethodPost, "/api/v1/auth/register", "", map[string]any{
		"email": email, "password": "IntegrationTest123!", "display_name": "Integration",
	})
	require.Equal(t, http.StatusCreated, resp.StatusCode, out)
	data := out["data"].(map[string]any)
	accessToken := data["tokens"].(map[string]any)["access_token"].(string)
	require.NotEmpty(t, accessToken)

	// 2. Login with the same credentials also works.
	resp, out = doJSON(t, app, http.MethodPost, "/api/v1/auth/login", "", map[string]any{
		"email": email, "password": "IntegrationTest123!",
	})
	require.Equal(t, http.StatusOK, resp.StatusCode, out)

	// 3. Register a device as that user.
	resp, out = doJSON(t, app, http.MethodPost, "/api/v1/devices", accessToken, map[string]any{
		"device_uuid": fmt.Sprintf("it-device-%d", time.Now().UnixNano()),
		"name":        "Integration Test Device",
	})
	require.Equal(t, http.StatusCreated, resp.StatusCode, out)
	devData := out["data"].(map[string]any)
	apiKey := devData["api_key"].(string)
	deviceID := devData["device"].(map[string]any)["id"].(string)
	require.NotEmpty(t, apiKey)

	// 4. Device list shows it.
	resp, out = doJSON(t, app, http.MethodGet, "/api/v1/devices", accessToken, nil)
	require.Equal(t, http.StatusOK, resp.StatusCode, out)
	devices := out["data"].(map[string]any)["devices"].([]any)
	require.NotEmpty(t, devices)

	// 5. Agent heartbeat requires a valid signature.
	hbBody, _ := json.Marshal(map[string]any{"battery_level": 77, "is_charging": true, "network_type": "wifi"})
	resp, out = signedAgentRequest(t, app, http.MethodPost, "/api/v1/agent/heartbeat", apiKey, hbBody)
	require.Equal(t, http.StatusOK, resp.StatusCode, out)

	// 6. An unsigned agent request is rejected (request signing is enforced).
	req := httptest.NewRequest(http.MethodPost, "/api/v1/agent/heartbeat", bytes.NewReader(hbBody))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Device-API-Key", apiKey)
	resp2, err := app.Test(req, fiber.TestConfig{Timeout: 5 * time.Second, FailOnTimeout: true})
	require.NoError(t, err)
	require.Equal(t, http.StatusUnauthorized, resp2.StatusCode)
	_ = resp2.Body.Close()

	// 7. Dashboard sees the latest health sample.
	resp, out = doJSON(t, app, http.MethodGet, "/api/v1/devices/"+deviceID+"/health", accessToken, nil)
	require.Equal(t, http.StatusOK, resp.StatusCode, out)
	health := out["data"].(map[string]any)["health"].(map[string]any)
	require.Equal(t, float64(77), health["battery_level"])

	// 8. Signed SMS ingest, then delivery-status update round trip.
	smsBody, _ := json.Marshal(map[string]any{
		"direction": "inbound", "address": "+15551234", "body": "code 123456", "sim_slot": 0,
	})
	resp, out = signedAgentRequest(t, app, http.MethodPost, "/api/v1/agent/sms", apiKey, smsBody)
	require.Equal(t, http.StatusCreated, resp.StatusCode, out)
	smsID := out["data"].(map[string]any)["sms"].(map[string]any)["id"].(string)

	statusBody, _ := json.Marshal(map[string]any{"status": "delivered", "delivered_at": time.Now().UTC().Format(time.RFC3339)})
	resp, out = signedAgentRequest(t, app, http.MethodPost, "/api/v1/agent/sms/"+smsID+"/status", apiKey, statusBody)
	require.Equal(t, http.StatusOK, resp.StatusCode, out)

	resp, out = doJSON(t, app, http.MethodGet, "/api/v1/devices/"+deviceID+"/sms", accessToken, nil)
	require.Equal(t, http.StatusOK, resp.StatusCode, out)
	smsList := out["data"].(map[string]any)["sms"].([]any)
	require.NotEmpty(t, smsList)
	require.Equal(t, "delivered", smsList[0].(map[string]any)["status"])

	// 9. Replaying the exact same signed request (same nonce) is rejected.
	resp3, out3 := signedAgentRequestWithNonce(t, app, apiKey, hbBody, "fixed-nonce")
	require.Equal(t, http.StatusOK, resp3.StatusCode, out3)
	resp4, _ := signedAgentRequestWithNonce(t, app, apiKey, hbBody, "fixed-nonce")
	require.Equal(t, http.StatusUnauthorized, resp4.StatusCode)
}

func signedAgentRequestWithNonce(t *testing.T, app *fiber.App, apiKey string, body []byte, nonce string) (*http.Response, map[string]any) {
	t.Helper()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/agent/heartbeat", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Device-API-Key", apiKey)
	ts := time.Now().Unix()
	req.Header.Set("X-Timestamp", strconv.FormatInt(ts, 10))
	req.Header.Set("X-Signature", signHMAC(apiKey, ts, body))
	req.Header.Set("X-Request-ID", nonce)
	resp, err := app.Test(req, fiber.TestConfig{Timeout: 5 * time.Second, FailOnTimeout: true})
	require.NoError(t, err)
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	var out map[string]any
	if len(raw) > 0 {
		_ = json.Unmarshal(raw, &out)
	}
	return resp, out
}

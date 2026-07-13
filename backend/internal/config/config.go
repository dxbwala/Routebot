package config

import (
	"encoding/hex"
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/joho/godotenv"
)

// Config holds runtime configuration loaded from environment variables.
type Config struct {
	AppEnv                   string
	AppPort                  string
	AppBaseURL               string
	JWTSecret                string
	JWTAccessTTL             time.Duration
	JWTRefreshTTL            time.Duration
	DeviceAPIKeyPepper       string
	WebhookHMACSecret        string
	RequestSigningKey        []byte
	RequestSignatureMaxSkew  time.Duration
	MediaStoragePath         string
	CORSOrigins              string
	PostgresDSN              string
	RedisAddr                string
	RedisPassword            string
	RedisDB                  int
	HeartbeatIntervalSeconds int
	OfflineThresholdSeconds  int
}

// Load reads configuration from the environment (.env optional).
func Load() (*Config, error) {
	_ = godotenv.Load()
	_ = godotenv.Load("../.env")

	accessTTL, err := time.ParseDuration(getEnv("JWT_ACCESS_TTL", "15m"))
	if err != nil {
		return nil, fmt.Errorf("JWT_ACCESS_TTL: %w", err)
	}
	refreshTTL, err := time.ParseDuration(getEnv("JWT_REFRESH_TTL", "168h"))
	if err != nil {
		return nil, fmt.Errorf("JWT_REFRESH_TTL: %w", err)
	}

	cfg := &Config{
		AppEnv:                   getEnv("APP_ENV", "development"),
		AppPort:                  getEnv("APP_PORT", "8080"),
		AppBaseURL:               getEnv("APP_BASE_URL", "http://localhost:8080"),
		JWTSecret:                getEnv("JWT_SECRET", ""),
		JWTAccessTTL:             accessTTL,
		JWTRefreshTTL:            refreshTTL,
		DeviceAPIKeyPepper:       getEnv("DEVICE_API_KEY_PEPPER", ""),
		WebhookHMACSecret:        getEnv("WEBHOOK_HMAC_SECRET", ""),
		MediaStoragePath:         getEnv("MEDIA_STORAGE_PATH", "./storage/media"),
		CORSOrigins:              getEnv("CORS_ORIGINS", "*"),
		RedisAddr:                getEnv("REDIS_ADDR", "localhost:6379"),
		RedisPassword:            getEnv("REDIS_PASSWORD", ""),
		RedisDB:                  getEnvInt("REDIS_DB", 0),
		HeartbeatIntervalSeconds: getEnvInt("HEARTBEAT_INTERVAL_SECONDS", 30),
		OfflineThresholdSeconds:  getEnvInt("OFFLINE_THRESHOLD_SECONDS", 90),
	}

	cfg.PostgresDSN = fmt.Sprintf(
		"postgres://%s:%s@%s:%s/%s?sslmode=%s",
		getEnv("POSTGRES_USER", "routebot"),
		getEnv("POSTGRES_PASSWORD", "routebot"),
		getEnv("POSTGRES_HOST", "localhost"),
		getEnv("POSTGRES_PORT", "5432"),
		getEnv("POSTGRES_DB", "routebot"),
		getEnv("POSTGRES_SSLMODE", "disable"),
	)

	if len(cfg.JWTSecret) < 32 {
		return nil, fmt.Errorf("JWT_SECRET must be at least 32 characters")
	}
	if cfg.DeviceAPIKeyPepper == "" {
		return nil, fmt.Errorf("DEVICE_API_KEY_PEPPER is required")
	}
	if cfg.WebhookHMACSecret == "" {
		return nil, fmt.Errorf("WEBHOOK_HMAC_SECRET is required")
	}

	signingKeyHex := getEnv("REQUEST_SIGNING_KEY", "")
	if signingKeyHex == "" {
		return nil, fmt.Errorf("REQUEST_SIGNING_KEY is required (64 hex chars / 32 bytes)")
	}
	signingKey, err := hex.DecodeString(signingKeyHex)
	if err != nil || len(signingKey) != 32 {
		return nil, fmt.Errorf("REQUEST_SIGNING_KEY must be 64 hex characters (32 bytes)")
	}
	cfg.RequestSigningKey = signingKey

	skew, err := time.ParseDuration(getEnv("REQUEST_SIGNATURE_MAX_SKEW", "5m"))
	if err != nil {
		return nil, fmt.Errorf("REQUEST_SIGNATURE_MAX_SKEW: %w", err)
	}
	cfg.RequestSignatureMaxSkew = skew

	return cfg, nil
}

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func getEnvInt(key string, fallback int) int {
	v := os.Getenv(key)
	if v == "" {
		return fallback
	}
	n, err := strconv.Atoi(v)
	if err != nil {
		return fallback
	}
	return n
}

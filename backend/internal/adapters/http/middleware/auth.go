package middleware

import (
	"strings"

	"github.com/gofiber/fiber/v3"
	"github.com/google/uuid"
	"github.com/routedns/routebot/backend/internal/domain"
	"github.com/routedns/routebot/backend/internal/pkg/auth"
	"github.com/routedns/routebot/backend/internal/pkg/response"
	"github.com/routedns/routebot/backend/internal/service"
)

const (
	CtxUserID   = "user_id"
	CtxUserRole = "user_role"
	CtxDevice   = "device"
)

func JWT(tokens *auth.Manager) fiber.Handler {
	return func(c fiber.Ctx) error {
		h := c.Get("Authorization")
		if !strings.HasPrefix(h, "Bearer ") {
			return response.Fail(c, fiber.StatusUnauthorized, "unauthorized", "missing bearer token")
		}
		claims, err := tokens.Parse(strings.TrimPrefix(h, "Bearer "))
		if err != nil {
			return response.Fail(c, fiber.StatusUnauthorized, "unauthorized", "invalid token")
		}
		c.Locals(CtxUserID, claims.UserID)
		c.Locals(CtxUserRole, claims.Role)
		return c.Next()
	}
}

func DeviceAPIKey(devices *service.DeviceService) fiber.Handler {
	return func(c fiber.Ctx) error {
		key := c.Get("X-Device-API-Key")
		if key == "" {
			return response.Fail(c, fiber.StatusUnauthorized, "unauthorized", "missing device api key")
		}
		d, err := devices.AuthenticateAPIKey(c.Context(), key)
		if err != nil {
			return response.Fail(c, fiber.StatusUnauthorized, "unauthorized", "invalid device api key")
		}
		c.Locals(CtxDevice, d)
		return c.Next()
	}
}

func UserID(c fiber.Ctx) uuid.UUID {
	v, _ := c.Locals(CtxUserID).(uuid.UUID)
	return v
}

func Device(c fiber.Ctx) *domain.Device {
	v, _ := c.Locals(CtxDevice).(*domain.Device)
	return v
}

func RequestID() fiber.Handler {
	return func(c fiber.Ctx) error {
		id := c.Get("X-Request-ID")
		if id == "" {
			id = uuid.NewString()
		}
		c.Set("X-Request-ID", id)
		c.Locals("request_id", id)
		return c.Next()
	}
}

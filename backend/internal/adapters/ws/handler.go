package ws

import (
	"context"
	"encoding/json"
	"time"

	"github.com/gofiber/contrib/v3/websocket"
	"github.com/gofiber/fiber/v3"
	"github.com/routedns/routebot/backend/internal/pkg/logger"
	"github.com/routedns/routebot/backend/internal/pkg/response"
	"github.com/routedns/routebot/backend/internal/service"
)

type Handler struct {
	devices  *service.DeviceService
	commands *service.CommandService
	hub      *Hub
	log      *logger.Logger
}

func NewHandler(devices *service.DeviceService, commands *service.CommandService, hub *Hub, log *logger.Logger) *Handler {
	return &Handler{devices: devices, commands: commands, hub: hub, log: log}
}

// Endpoint returns a Fiber handler that upgrades and serves the agent WebSocket.
func (h *Handler) Endpoint() fiber.Handler {
	return websocket.New(func(conn *websocket.Conn) {
		ctx := context.Background()
		apiKey := conn.Query("api_key")
		if apiKey == "" {
			apiKey = conn.Headers("X-Device-API-Key")
		}
		if apiKey == "" {
			_ = conn.WriteJSON(map[string]any{"type": "error", "message": "missing api key"})
			_ = conn.Close()
			return
		}

		device, err := h.devices.AuthenticateAPIKey(ctx, apiKey)
		if err != nil {
			_ = conn.WriteJSON(map[string]any{"type": "error", "message": "unauthorized"})
			_ = conn.Close()
			return
		}

		h.hub.Register(device.ID, func(b []byte) error {
			return conn.WriteMessage(websocket.TextMessage, b)
		})
		defer func() {
			h.hub.Unregister(device.ID)
			_ = h.devices.MarkDisconnected(context.Background(), device.ID)
		}()

		_ = h.devices.MarkConnected(ctx, device.ID)
		_ = h.commands.FlushQueued(ctx, device.ID)
		_ = conn.WriteJSON(map[string]any{
			"type":      "welcome",
			"device_id": device.ID,
			"ts":        time.Now().UTC().Unix(),
		})

		_ = conn.SetReadDeadline(time.Now().Add(90 * time.Second))
		conn.SetPongHandler(func(string) error {
			return conn.SetReadDeadline(time.Now().Add(90 * time.Second))
		})

		for {
			_, data, err := conn.ReadMessage()
			if err != nil {
				h.log.Info("ws disconnected", map[string]any{"device_id": device.ID.String()})
				return
			}
			_ = conn.SetReadDeadline(time.Now().Add(90 * time.Second))

			var msg map[string]any
			if err := json.Unmarshal(data, &msg); err != nil {
				continue
			}
			switch msg["type"] {
			case "ping":
				_ = conn.WriteJSON(map[string]any{"type": "pong", "ts": time.Now().UTC().Unix()})
			case "heartbeat":
				_ = h.devices.MarkConnected(context.Background(), device.ID)
				_ = conn.WriteJSON(map[string]any{"type": "heartbeat_ack", "ts": time.Now().UTC().Unix()})
			default:
				h.log.Debug("ws unknown message", map[string]any{"type": msg["type"]})
			}
		}
	}, websocket.Config{
		Origins:          []string{"*"},
		AllowEmptyOrigin: true,
	})
}

// UpgradeGuard rejects non-websocket requests before the upgrade handler.
func UpgradeGuard() fiber.Handler {
	return func(c fiber.Ctx) error {
		if websocket.IsWebSocketUpgrade(c) {
			return c.Next()
		}
		return response.Fail(c, fiber.StatusUpgradeRequired, "upgrade_required", "websocket upgrade required")
	}
}

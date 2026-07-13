package handlers

import (
	"bufio"
	"errors"
	"time"

	"github.com/gofiber/fiber/v3"
	"github.com/google/uuid"
	"github.com/routedns/routebot/backend/internal/adapters/http/middleware"
	"github.com/routedns/routebot/backend/internal/domain"
	"github.com/routedns/routebot/backend/internal/pkg/response"
	"github.com/routedns/routebot/backend/internal/service"
)

type AuthHandler struct{ svc *service.AuthService }

func NewAuthHandler(svc *service.AuthService) *AuthHandler { return &AuthHandler{svc: svc} }

func (h *AuthHandler) Register(c fiber.Ctx) error {
	var in service.RegisterInput
	if err := c.Bind().Body(&in); err != nil {
		return response.Fail(c, fiber.StatusBadRequest, "bad_request", "invalid body")
	}
	user, tokens, err := h.svc.Register(c.Context(), in, c.IP())
	if err != nil {
		return mapErr(c, err)
	}
	return response.Created(c, fiber.Map{"user": user, "tokens": tokens})
}

func (h *AuthHandler) Login(c fiber.Ctx) error {
	var in service.LoginInput
	if err := c.Bind().Body(&in); err != nil {
		return response.Fail(c, fiber.StatusBadRequest, "bad_request", "invalid body")
	}
	user, tokens, err := h.svc.Login(c.Context(), in, c.IP())
	if err != nil {
		return mapErr(c, err)
	}
	return response.OK(c, fiber.Map{"user": user, "tokens": tokens})
}

func (h *AuthHandler) Refresh(c fiber.Ctx) error {
	var body struct {
		RefreshToken string `json:"refresh_token"`
	}
	if err := c.Bind().Body(&body); err != nil || body.RefreshToken == "" {
		return response.Fail(c, fiber.StatusBadRequest, "bad_request", "refresh_token required")
	}
	tokens, err := h.svc.Refresh(c.Context(), body.RefreshToken)
	if err != nil {
		return mapErr(c, err)
	}
	return response.OK(c, fiber.Map{"tokens": tokens})
}

type DeviceHandler struct{ svc *service.DeviceService }

func NewDeviceHandler(svc *service.DeviceService) *DeviceHandler { return &DeviceHandler{svc: svc} }

func (h *DeviceHandler) Register(c fiber.Ctx) error {
	var in service.RegisterDeviceInput
	if err := c.Bind().Body(&in); err != nil {
		return response.Fail(c, fiber.StatusBadRequest, "bad_request", "invalid body")
	}
	res, err := h.svc.Register(c.Context(), middleware.UserID(c), in, c.IP())
	if err != nil {
		return mapErr(c, err)
	}
	return response.Created(c, res)
}

func (h *DeviceHandler) List(c fiber.Ctx) error {
	items, err := h.svc.List(c.Context(), middleware.UserID(c))
	if err != nil {
		return mapErr(c, err)
	}
	return response.OK(c, fiber.Map{"devices": items})
}

func (h *DeviceHandler) Get(c fiber.Ctx) error {
	id, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return response.Fail(c, fiber.StatusBadRequest, "bad_request", "invalid id")
	}
	d, err := h.svc.Get(c.Context(), middleware.UserID(c), id)
	if err != nil {
		return mapErr(c, err)
	}
	return response.OK(c, fiber.Map{"device": d})
}

func (h *DeviceHandler) Health(c fiber.Ctx) error {
	id, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return response.Fail(c, fiber.StatusBadRequest, "bad_request", "invalid id")
	}
	hb, err := h.svc.LatestHealth(c.Context(), middleware.UserID(c), id)
	if err != nil {
		return mapErr(c, err)
	}
	return response.OK(c, fiber.Map{"health": hb})
}

func (h *DeviceHandler) HealthHistory(c fiber.Ctx) error {
	id, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return response.Fail(c, fiber.StatusBadRequest, "bad_request", "invalid id")
	}
	items, err := h.svc.HealthHistory(c.Context(), middleware.UserID(c), id, 200)
	if err != nil {
		return mapErr(c, err)
	}
	return response.OK(c, fiber.Map{"health": items})
}

// LiveStatus streams online/offline + heartbeat push updates via Server-Sent
// Events, so the dashboard doesn't need to poll for "live status".
func (h *DeviceHandler) LiveStatus(c fiber.Ctx) error {
	id, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return response.Fail(c, fiber.StatusBadRequest, "bad_request", "invalid id")
	}
	ch, cancel, err := h.svc.SubscribeStatus(c.Context(), middleware.UserID(c), id)
	if err != nil {
		return mapErr(c, err)
	}

	c.Set("Content-Type", "text/event-stream")
	c.Set("Cache-Control", "no-cache")
	c.Set("Connection", "keep-alive")

	return c.SendStreamWriter(func(w *bufio.Writer) {
		defer cancel()
		for msg := range ch {
			if _, err := w.WriteString("data: "); err != nil {
				return
			}
			if _, err := w.Write(msg); err != nil {
				return
			}
			if _, err := w.WriteString("\n\n"); err != nil {
				return
			}
			if err := w.Flush(); err != nil {
				return
			}
		}
	})
}

func (h *DeviceHandler) ReportCrash(c fiber.Ctx) error {
	var body struct {
		Message    string `json:"message"`
		StackTrace string `json:"stack_trace"`
		AppVersion string `json:"app_version"`
	}
	if err := c.Bind().Body(&body); err != nil {
		return response.Fail(c, fiber.StatusBadRequest, "bad_request", "invalid body")
	}
	if err := h.svc.ReportCrash(c.Context(), middleware.Device(c), body.Message, body.StackTrace, body.AppVersion); err != nil {
		return mapErr(c, err)
	}
	return response.Created(c, fiber.Map{"reported": true})
}

func (h *DeviceHandler) Heartbeat(c fiber.Ctx) error {
	var hb domain.DeviceHeartbeat
	if err := c.Bind().Body(&hb); err != nil {
		return response.Fail(c, fiber.StatusBadRequest, "bad_request", "invalid body")
	}
	if err := h.svc.Heartbeat(c.Context(), middleware.Device(c), &hb); err != nil {
		return mapErr(c, err)
	}
	return response.OK(c, fiber.Map{"heartbeat": hb})
}

type EventHandler struct{ svc *service.EventService }

func NewEventHandler(svc *service.EventService) *EventHandler { return &EventHandler{svc: svc} }

func (h *EventHandler) IngestSMS(c fiber.Ctx) error {
	var msg domain.SMSMessage
	if err := c.Bind().Body(&msg); err != nil {
		return response.Fail(c, fiber.StatusBadRequest, "bad_request", "invalid body")
	}
	if err := h.svc.IngestSMS(c.Context(), middleware.Device(c), &msg); err != nil {
		return mapErr(c, err)
	}
	return response.Created(c, fiber.Map{"sms": msg})
}

var validSMSStatuses = map[string]struct{}{
	"sent": {}, "failed": {}, "delivered": {}, "delivery_failed": {},
}

func (h *EventHandler) UpdateSMSStatus(c fiber.Ctx) error {
	id, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return response.Fail(c, fiber.StatusBadRequest, "bad_request", "invalid id")
	}
	var body struct {
		Status      string `json:"status"`
		DeliveredAt string `json:"delivered_at"`
	}
	if err := c.Bind().Body(&body); err != nil {
		return response.Fail(c, fiber.StatusBadRequest, "bad_request", "invalid body")
	}
	if _, ok := validSMSStatuses[body.Status]; !ok {
		return response.Fail(c, fiber.StatusBadRequest, "bad_request", "invalid status")
	}
	var deliveredAt *time.Time
	if body.DeliveredAt != "" {
		t, err := time.Parse(time.RFC3339, body.DeliveredAt)
		if err != nil {
			return response.Fail(c, fiber.StatusBadRequest, "bad_request", "invalid delivered_at")
		}
		deliveredAt = &t
	}
	if err := h.svc.UpdateSMSStatus(c.Context(), middleware.Device(c), id, body.Status, deliveredAt); err != nil {
		return mapErr(c, err)
	}
	return response.OK(c, fiber.Map{"updated": true})
}

func (h *EventHandler) ListSMS(c fiber.Ctx) error {
	id, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return response.Fail(c, fiber.StatusBadRequest, "bad_request", "invalid id")
	}
	items, err := h.svc.ListSMS(c.Context(), middleware.UserID(c), id, 100)
	if err != nil {
		return mapErr(c, err)
	}
	return response.OK(c, fiber.Map{"sms": items})
}

func (h *EventHandler) IngestOTP(c fiber.Ctx) error {
	var evt domain.OTPEvent
	if err := c.Bind().Body(&evt); err != nil {
		return response.Fail(c, fiber.StatusBadRequest, "bad_request", "invalid body")
	}
	if err := h.svc.IngestOTP(c.Context(), middleware.Device(c), &evt); err != nil {
		return mapErr(c, err)
	}
	return response.Created(c, fiber.Map{"otp": evt})
}

func (h *EventHandler) ListOTP(c fiber.Ctx) error {
	id, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return response.Fail(c, fiber.StatusBadRequest, "bad_request", "invalid id")
	}
	items, err := h.svc.ListOTP(c.Context(), middleware.UserID(c), id, 100)
	if err != nil {
		return mapErr(c, err)
	}
	return response.OK(c, fiber.Map{"otp": items})
}

func (h *EventHandler) IngestNotification(c fiber.Ctx) error {
	var evt domain.NotificationEvent
	if err := c.Bind().Body(&evt); err != nil {
		return response.Fail(c, fiber.StatusBadRequest, "bad_request", "invalid body")
	}
	if err := h.svc.IngestNotification(c.Context(), middleware.Device(c), &evt); err != nil {
		return mapErr(c, err)
	}
	return response.Created(c, fiber.Map{"notification": evt})
}

func (h *EventHandler) ListNotifications(c fiber.Ctx) error {
	id, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return response.Fail(c, fiber.StatusBadRequest, "bad_request", "invalid id")
	}
	items, err := h.svc.ListNotifications(c.Context(), middleware.UserID(c), id, 100)
	if err != nil {
		return mapErr(c, err)
	}
	return response.OK(c, fiber.Map{"notifications": items})
}

func (h *EventHandler) IngestCall(c fiber.Ctx) error {
	var evt domain.CallEvent
	if err := c.Bind().Body(&evt); err != nil {
		return response.Fail(c, fiber.StatusBadRequest, "bad_request", "invalid body")
	}
	if err := h.svc.IngestCall(c.Context(), middleware.Device(c), &evt); err != nil {
		return mapErr(c, err)
	}
	return response.Created(c, fiber.Map{"call": evt})
}

func (h *EventHandler) ListCalls(c fiber.Ctx) error {
	id, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return response.Fail(c, fiber.StatusBadRequest, "bad_request", "invalid id")
	}
	items, err := h.svc.ListCalls(c.Context(), middleware.UserID(c), id, 100)
	if err != nil {
		return mapErr(c, err)
	}
	return response.OK(c, fiber.Map{"calls": items})
}

func mapErr(c fiber.Ctx, err error) error {
	switch {
	case errors.Is(err, service.ErrInvalidInput):
		return response.Fail(c, fiber.StatusBadRequest, "bad_request", err.Error())
	case errors.Is(err, service.ErrUnauthorized):
		return response.Fail(c, fiber.StatusUnauthorized, "unauthorized", "unauthorized")
	case errors.Is(err, service.ErrForbidden):
		return response.Fail(c, fiber.StatusForbidden, "forbidden", "forbidden")
	case errors.Is(err, service.ErrNotFound):
		return response.Fail(c, fiber.StatusNotFound, "not_found", "not found")
	default:
		return response.Fail(c, fiber.StatusInternalServerError, "internal_error", "internal error")
	}
}

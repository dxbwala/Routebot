package handlers

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"

	"github.com/gofiber/fiber/v3"
	"github.com/google/uuid"
	"github.com/routedns/routebot/backend/internal/adapters/http/middleware"
	"github.com/routedns/routebot/backend/internal/domain"
	"github.com/routedns/routebot/backend/internal/pkg/response"
	"github.com/routedns/routebot/backend/internal/service"
)

type CommandHandler struct{ svc *service.CommandService }

func NewCommandHandler(svc *service.CommandService) *CommandHandler {
	return &CommandHandler{svc: svc}
}

func (h *CommandHandler) Create(c fiber.Ctx) error {
	deviceID, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return response.Fail(c, fiber.StatusBadRequest, "bad_request", "invalid id")
	}
	var in service.CreateCommandInput
	if err := c.Bind().Body(&in); err != nil {
		return response.Fail(c, fiber.StatusBadRequest, "bad_request", "invalid body")
	}
	cmd, err := h.svc.Create(c.Context(), middleware.UserID(c), deviceID, middleware.UserID(c), in, c.IP())
	if err != nil {
		return mapErr(c, err)
	}
	return response.Created(c, fiber.Map{"command": cmd})
}

func (h *CommandHandler) List(c fiber.Ctx) error {
	deviceID, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return response.Fail(c, fiber.StatusBadRequest, "bad_request", "invalid id")
	}
	items, err := h.svc.List(c.Context(), middleware.UserID(c), deviceID, 100)
	if err != nil {
		return mapErr(c, err)
	}
	return response.OK(c, fiber.Map{"commands": items})
}

func (h *CommandHandler) Ack(c fiber.Ctx) error {
	commandID, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return response.Fail(c, fiber.StatusBadRequest, "bad_request", "invalid id")
	}
	var body struct {
		Status       domain.CommandStatus `json:"status"`
		Result       json.RawMessage      `json:"result"`
		ErrorMessage string               `json:"error_message"`
	}
	if err := c.Bind().Body(&body); err != nil {
		return response.Fail(c, fiber.StatusBadRequest, "bad_request", "invalid body")
	}
	if err := h.svc.Ack(c.Context(), middleware.Device(c), commandID, body.Status, body.Result, body.ErrorMessage); err != nil {
		return mapErr(c, err)
	}
	return response.OK(c, fiber.Map{"acked": true})
}

type MediaHandler struct{ svc *service.MediaService }

func NewMediaHandler(svc *service.MediaService) *MediaHandler { return &MediaHandler{svc: svc} }

func (h *MediaHandler) Upload(c fiber.Ctx) error {
	mediaType := domain.MediaType(c.FormValue("media_type"))
	switch mediaType {
	case domain.MediaAudio, domain.MediaVideo, domain.MediaScreenshot:
	default:
		return response.Fail(c, fiber.StatusBadRequest, "bad_request", "invalid media_type")
	}
	file, err := c.FormFile("file")
	if err != nil {
		return response.Fail(c, fiber.StatusBadRequest, "bad_request", "file required")
	}
	f, err := file.Open()
	if err != nil {
		return mapErr(c, err)
	}
	defer f.Close()
	data, err := io.ReadAll(f)
	if err != nil {
		return mapErr(c, err)
	}
	sum := sha256.Sum256(data)
	var commandID *uuid.UUID
	if v := c.FormValue("command_id"); v != "" {
		id, err := uuid.Parse(v)
		if err != nil {
			return response.Fail(c, fiber.StatusBadRequest, "bad_request", "invalid command_id")
		}
		commandID = &id
	}
	contentType := file.Header.Get("Content-Type")
	if contentType == "" {
		contentType = "application/octet-stream"
	}
	m, err := h.svc.Store(c.Context(), middleware.Device(c), mediaType, commandID, contentType, data, hex.EncodeToString(sum[:]))
	if err != nil {
		return mapErr(c, err)
	}
	return response.Created(c, fiber.Map{"media": m})
}

func (h *MediaHandler) List(c fiber.Ctx) error {
	deviceID, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return response.Fail(c, fiber.StatusBadRequest, "bad_request", "invalid id")
	}
	items, err := h.svc.List(c.Context(), middleware.UserID(c), deviceID, 50)
	if err != nil {
		return mapErr(c, err)
	}
	return response.OK(c, fiber.Map{"media": items})
}

func (h *MediaHandler) Download(c fiber.Ctx) error {
	id, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return response.Fail(c, fiber.StatusBadRequest, "bad_request", "invalid id")
	}
	m, data, err := h.svc.GetFile(c.Context(), middleware.UserID(c), id)
	if err != nil {
		return mapErr(c, err)
	}
	c.Set("Content-Type", m.ContentType)
	c.Set("Content-Disposition", "attachment; filename=\""+m.ID.String()+"\"")
	return c.Send(data)
}

type WebhookHandler struct{ svc *service.WebhookService }

func NewWebhookHandler(svc *service.WebhookService) *WebhookHandler {
	return &WebhookHandler{svc: svc}
}

func (h *WebhookHandler) Create(c fiber.Ctx) error {
	var in service.CreateWebhookInput
	if err := c.Bind().Body(&in); err != nil {
		return response.Fail(c, fiber.StatusBadRequest, "bad_request", "invalid body")
	}
	ep, err := h.svc.Create(c.Context(), middleware.UserID(c), in)
	if err != nil {
		return mapErr(c, err)
	}
	return response.Created(c, fiber.Map{"webhook": ep})
}

func (h *WebhookHandler) List(c fiber.Ctx) error {
	items, err := h.svc.List(c.Context(), middleware.UserID(c))
	if err != nil {
		return mapErr(c, err)
	}
	return response.OK(c, fiber.Map{"webhooks": items})
}

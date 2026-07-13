package handlers

import (
	"github.com/gofiber/fiber/v3"
	"github.com/routedns/routebot/backend/internal/adapters/http/middleware"
	"github.com/routedns/routebot/backend/internal/pkg/response"
	"github.com/routedns/routebot/backend/internal/service"
)

type EnrollmentHandler struct{ svc *service.EnrollmentService }

func NewEnrollmentHandler(svc *service.EnrollmentService) *EnrollmentHandler {
	return &EnrollmentHandler{svc: svc}
}

func (h *EnrollmentHandler) Create(c fiber.Ctx) error {
	var body struct {
		DeviceName string `json:"device_name"`
	}
	_ = c.Bind().Body(&body)
	res, err := h.svc.Create(c.Context(), middleware.UserID(c), body.DeviceName, c.IP())
	if err != nil {
		return mapErr(c, err)
	}
	return response.Created(c, fiber.Map{"enrollment": res})
}

func (h *EnrollmentHandler) Claim(c fiber.Ctx) error {
	var in service.ClaimEnrollmentInput
	if err := c.Bind().Body(&in); err != nil {
		return response.Fail(c, fiber.StatusBadRequest, "bad_request", "invalid body")
	}
	res, err := h.svc.Claim(c.Context(), in, c.IP())
	if err != nil {
		return mapErr(c, err)
	}
	return response.Created(c, res)
}

package response

import (
	"github.com/gofiber/fiber/v3"
)

type ErrorBody struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

type Envelope struct {
	Success bool       `json:"success"`
	Data    any        `json:"data,omitempty"`
	Error   *ErrorBody `json:"error,omitempty"`
}

func OK(c fiber.Ctx, data any) error {
	return c.Status(fiber.StatusOK).JSON(Envelope{Success: true, Data: data})
}

func Created(c fiber.Ctx, data any) error {
	return c.Status(fiber.StatusCreated).JSON(Envelope{Success: true, Data: data})
}

func Fail(c fiber.Ctx, status int, code, message string) error {
	return c.Status(status).JSON(Envelope{
		Success: false,
		Error:   &ErrorBody{Code: code, Message: message},
	})
}

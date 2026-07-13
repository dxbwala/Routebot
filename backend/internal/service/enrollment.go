package service

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"time"

	"github.com/google/uuid"
	"github.com/routedns/routebot/backend/internal/domain"
	"github.com/routedns/routebot/backend/internal/ports"
)

// EnrollmentStore stores single-use pairing tokens.
type EnrollmentStore interface {
	Put(ctx context.Context, token string, ownerID uuid.UUID, deviceName string, ttl time.Duration) error
	Take(ctx context.Context, token string) (ownerID uuid.UUID, deviceName string, err error)
}

type EnrollmentService struct {
	store   EnrollmentStore
	devices *DeviceService
	audit   ports.AuditRepository
	ttl     time.Duration
}

func NewEnrollmentService(store EnrollmentStore, devices *DeviceService, audit ports.AuditRepository) *EnrollmentService {
	return &EnrollmentService{
		store:   store,
		devices: devices,
		audit:   audit,
		ttl:     10 * time.Minute,
	}
}

type CreateEnrollmentResult struct {
	Token      string    `json:"token"`
	ExpiresAt  time.Time `json:"expires_at"`
	TTLSeconds int       `json:"ttl_seconds"`
}

func (s *EnrollmentService) Create(ctx context.Context, ownerID uuid.UUID, deviceName string, ip string) (*CreateEnrollmentResult, error) {
	token, err := randomEnrollmentToken()
	if err != nil {
		return nil, err
	}
	if err := s.store.Put(ctx, token, ownerID, deviceName, s.ttl); err != nil {
		return nil, err
	}
	_ = s.audit.Insert(ctx, &domain.AuditLog{
		ActorType: "user", ActorID: ownerID.String(), Action: "enrollment.create",
		ResourceType: "enrollment", ResourceID: token[:12], IPAddress: ip,
	})
	return &CreateEnrollmentResult{
		Token:      token,
		ExpiresAt:  time.Now().UTC().Add(s.ttl),
		TTLSeconds: int(s.ttl.Seconds()),
	}, nil
}

type ClaimEnrollmentInput struct {
	Token          string          `json:"token"`
	DeviceUUID     string          `json:"device_uuid"`
	Name           string          `json:"name"`
	Manufacturer   string          `json:"manufacturer"`
	Model          string          `json:"model"`
	AndroidVersion string          `json:"android_version"`
	AppVersion     string          `json:"app_version"`
	Metadata       json.RawMessage `json:"metadata"`
}

func (s *EnrollmentService) Claim(ctx context.Context, in ClaimEnrollmentInput, ip string) (*RegisterDeviceResult, error) {
	if in.Token == "" || in.DeviceUUID == "" {
		return nil, ErrInvalidInput
	}
	ownerID, presetName, err := s.store.Take(ctx, in.Token)
	if err != nil {
		return nil, ErrUnauthorized
	}
	name := in.Name
	if name == "" {
		name = presetName
	}
	if name == "" {
		name = "RouteBot Device"
	}
	res, err := s.devices.Register(ctx, ownerID, RegisterDeviceInput{
		DeviceUUID:     in.DeviceUUID,
		Name:           name,
		Manufacturer:   in.Manufacturer,
		Model:          in.Model,
		AndroidVersion: in.AndroidVersion,
		AppVersion:     in.AppVersion,
		Metadata:       in.Metadata,
	}, ip)
	if err != nil {
		return nil, err
	}
	_ = s.audit.Insert(ctx, &domain.AuditLog{
		ActorType: "device", ActorID: res.Device.ID.String(), Action: "enrollment.claim",
		ResourceType: "device", ResourceID: res.Device.ID.String(), IPAddress: ip,
	})
	return res, nil
}

func randomEnrollmentToken() (string, error) {
	b := make([]byte, 24)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return "enr_" + base64.RawURLEncoding.EncodeToString(b), nil
}

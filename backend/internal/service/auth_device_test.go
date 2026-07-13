package service_test

import (
	"context"
	"encoding/json"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/routedns/routebot/backend/internal/domain"
	"github.com/routedns/routebot/backend/internal/pkg/auth"
	"github.com/routedns/routebot/backend/internal/service"
	"github.com/stretchr/testify/require"
)

type memUsers struct {
	byEmail map[string]*domain.User
	byID    map[uuid.UUID]*domain.User
}

func newMemUsers() *memUsers {
	return &memUsers{byEmail: map[string]*domain.User{}, byID: map[uuid.UUID]*domain.User{}}
}

func (m *memUsers) Create(_ context.Context, user *domain.User) error {
	user.ID = uuid.New()
	user.CreatedAt = time.Now().UTC()
	user.UpdatedAt = user.CreatedAt
	user.IsActive = true
	cp := *user
	m.byEmail[user.Email] = &cp
	m.byID[user.ID] = &cp
	return nil
}

func (m *memUsers) GetByEmail(_ context.Context, email string) (*domain.User, error) {
	u, ok := m.byEmail[email]
	if !ok {
		return nil, service.ErrNotFound
	}
	cp := *u
	return &cp, nil
}

func (m *memUsers) GetByID(_ context.Context, id uuid.UUID) (*domain.User, error) {
	u, ok := m.byID[id]
	if !ok {
		return nil, service.ErrNotFound
	}
	cp := *u
	return &cp, nil
}

type memRefresh struct{ store map[string]uuid.UUID }

func (m *memRefresh) Store(_ context.Context, userID uuid.UUID, tokenHash string, _ time.Time) error {
	if m.store == nil {
		m.store = map[string]uuid.UUID{}
	}
	m.store[tokenHash] = userID
	return nil
}
func (m *memRefresh) GetValid(_ context.Context, tokenHash string) (uuid.UUID, error) {
	id, ok := m.store[tokenHash]
	if !ok {
		return uuid.Nil, service.ErrUnauthorized
	}
	return id, nil
}
func (m *memRefresh) Revoke(_ context.Context, tokenHash string) error {
	delete(m.store, tokenHash)
	return nil
}

type nopAudit struct{}

func (nopAudit) Insert(context.Context, *domain.AuditLog) error { return nil }

func TestAuthRegisterLogin(t *testing.T) {
	tokens := auth.NewManager("01234567890123456789012345678901", time.Minute, time.Hour)
	svc := service.NewAuthService(newMemUsers(), &memRefresh{}, tokens, nopAudit{}, time.Hour)
	user, pair, err := svc.Register(context.Background(), service.RegisterInput{
		Email: "a@b.com", Password: "password123", DisplayName: "A",
	}, "127.0.0.1")
	require.NoError(t, err)
	require.NotEmpty(t, pair.AccessToken)
	require.Equal(t, "a@b.com", user.Email)

	_, pair2, err := svc.Login(context.Background(), service.LoginInput{
		Email: "a@b.com", Password: "password123",
	}, "127.0.0.1")
	require.NoError(t, err)
	require.NotEmpty(t, pair2.AccessToken)

	_, _, err = svc.Login(context.Background(), service.LoginInput{
		Email: "a@b.com", Password: "wrongpass",
	}, "127.0.0.1")
	require.ErrorIs(t, err, service.ErrUnauthorized)
}

type memDevices struct {
	byID  map[uuid.UUID]*domain.Device
	byKey map[string]*domain.Device
}

func newMemDevices() *memDevices {
	return &memDevices{byID: map[uuid.UUID]*domain.Device{}, byKey: map[string]*domain.Device{}}
}

func (m *memDevices) Create(_ context.Context, d *domain.Device) error {
	d.ID = uuid.New()
	d.CreatedAt = time.Now().UTC()
	d.UpdatedAt = d.CreatedAt
	if d.Metadata == nil {
		d.Metadata = json.RawMessage(`{}`)
	}
	cp := *d
	m.byID[d.ID] = &cp
	m.byKey[d.APIKeyHash] = &cp
	return nil
}
func (m *memDevices) GetByID(_ context.Context, id uuid.UUID) (*domain.Device, error) {
	d, ok := m.byID[id]
	if !ok {
		return nil, service.ErrNotFound
	}
	cp := *d
	return &cp, nil
}
func (m *memDevices) GetByUUID(context.Context, string) (*domain.Device, error) {
	return nil, service.ErrNotFound
}
func (m *memDevices) GetByAPIKeyHash(_ context.Context, hash string) (*domain.Device, error) {
	d, ok := m.byKey[hash]
	if !ok {
		return nil, service.ErrNotFound
	}
	cp := *d
	return &cp, nil
}
func (m *memDevices) ListByOwner(_ context.Context, ownerID uuid.UUID) ([]domain.Device, error) {
	var out []domain.Device
	for _, d := range m.byID {
		if d.OwnerID == ownerID {
			out = append(out, *d)
		}
	}
	return out, nil
}
func (m *memDevices) UpdateProfile(context.Context, *domain.Device) error { return nil }
func (m *memDevices) UpdateStatus(_ context.Context, id uuid.UUID, status domain.DeviceStatus, lastSeen *time.Time) error {
	d := m.byID[id]
	d.Status = status
	d.LastSeenAt = lastSeen
	return nil
}
func (m *memDevices) MarkStaleOffline(context.Context, time.Time) (int64, error) { return 0, nil }

type memHB struct{ items []domain.DeviceHeartbeat }

func (m *memHB) Insert(_ context.Context, hb *domain.DeviceHeartbeat) error {
	hb.ID = int64(len(m.items) + 1)
	hb.ReportedAt = time.Now().UTC()
	m.items = append(m.items, *hb)
	return nil
}
func (m *memHB) LatestByDevice(context.Context, uuid.UUID) (*domain.DeviceHeartbeat, error) {
	return nil, service.ErrNotFound
}
func (m *memHB) ListByDevice(context.Context, uuid.UUID, int) ([]domain.DeviceHeartbeat, error) {
	return nil, nil
}

type nopPresence struct{}

func (nopPresence) SetOnline(context.Context, uuid.UUID, time.Duration) error { return nil }
func (nopPresence) IsOnline(context.Context, uuid.UUID) (bool, error)        { return true, nil }
func (nopPresence) Publish(context.Context, string, []byte) error            { return nil }
func (nopPresence) Subscribe(context.Context, string) (<-chan []byte, func(), error) {
	ch := make(chan []byte)
	return ch, func() {}, nil
}

type nopHooks struct{}

func (nopHooks) Dispatch(context.Context, uuid.UUID, string, string, any) error { return nil }

func TestDeviceRegisterAndAuth(t *testing.T) {
	devices := newMemDevices()
	svc := service.NewDeviceService(devices, &memHB{}, nopPresence{}, "pepper", 90, nopAudit{}, nopHooks{})
	owner := uuid.New()
	res, err := svc.Register(context.Background(), owner, service.RegisterDeviceInput{
		DeviceUUID: "dev-1", Name: "Phone",
	}, "127.0.0.1")
	require.NoError(t, err)
	require.NotEmpty(t, res.APIKey)

	d, err := svc.AuthenticateAPIKey(context.Background(), res.APIKey)
	require.NoError(t, err)
	require.Equal(t, res.Device.ID, d.ID)

	level := 50
	charging := true
	require.NoError(t, svc.Heartbeat(context.Background(), d, &domain.DeviceHeartbeat{
		BatteryLevel: &level, IsCharging: &charging,
	}))
}

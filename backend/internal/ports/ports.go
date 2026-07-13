package ports

import (
	"context"
	"encoding/json"
	"time"

	"github.com/google/uuid"
	"github.com/routedns/routebot/backend/internal/domain"
)

type UserRepository interface {
	Create(ctx context.Context, user *domain.User) error
	GetByEmail(ctx context.Context, email string) (*domain.User, error)
	GetByID(ctx context.Context, id uuid.UUID) (*domain.User, error)
	// UpsertByEmail inserts a user or updates password/display/role/active for an existing email.
	UpsertByEmail(ctx context.Context, user *domain.User) error
}

type RefreshTokenRepository interface {
	Store(ctx context.Context, userID uuid.UUID, tokenHash string, expiresAt time.Time) error
	GetValid(ctx context.Context, tokenHash string) (uuid.UUID, error)
	Revoke(ctx context.Context, tokenHash string) error
}

type DeviceRepository interface {
	Create(ctx context.Context, device *domain.Device) error
	GetByID(ctx context.Context, id uuid.UUID) (*domain.Device, error)
	GetByUUID(ctx context.Context, deviceUUID string) (*domain.Device, error)
	GetByAPIKeyHash(ctx context.Context, hash string) (*domain.Device, error)
	ListByOwner(ctx context.Context, ownerID uuid.UUID) ([]domain.Device, error)
	UpdateProfile(ctx context.Context, device *domain.Device) error
	UpdateStatus(ctx context.Context, id uuid.UUID, status domain.DeviceStatus, lastSeen *time.Time) error
	MarkStaleOffline(ctx context.Context, threshold time.Time) (int64, error)
}

type HeartbeatRepository interface {
	Insert(ctx context.Context, hb *domain.DeviceHeartbeat) error
	LatestByDevice(ctx context.Context, deviceID uuid.UUID) (*domain.DeviceHeartbeat, error)
	ListByDevice(ctx context.Context, deviceID uuid.UUID, limit int) ([]domain.DeviceHeartbeat, error)
}

type SMSRepository interface {
	Create(ctx context.Context, msg *domain.SMSMessage) error
	ListByDevice(ctx context.Context, deviceID uuid.UUID, limit int) ([]domain.SMSMessage, error)
	// UpdateStatus updates status/deliveredAt only for the row owned by deviceID, so a device
	// can only report delivery results for its own messages. Returns ErrNotFound-equivalent
	// (via 0 rows affected) if the id/deviceID pair doesn't match.
	UpdateStatus(ctx context.Context, id uuid.UUID, deviceID uuid.UUID, status string, deliveredAt *time.Time) (found bool, err error)
}

type OTPRepository interface {
	Create(ctx context.Context, evt *domain.OTPEvent) error
	ListByDevice(ctx context.Context, deviceID uuid.UUID, limit int) ([]domain.OTPEvent, error)
}

type NotificationRepository interface {
	Create(ctx context.Context, evt *domain.NotificationEvent) error
	ListByDevice(ctx context.Context, deviceID uuid.UUID, limit int) ([]domain.NotificationEvent, error)
}

type CallRepository interface {
	Create(ctx context.Context, evt *domain.CallEvent) error
	ListByDevice(ctx context.Context, deviceID uuid.UUID, limit int) ([]domain.CallEvent, error)
}

type CommandRepository interface {
	Create(ctx context.Context, cmd *domain.Command) error
	GetByID(ctx context.Context, id uuid.UUID) (*domain.Command, error)
	ListByDevice(ctx context.Context, deviceID uuid.UUID, limit int) ([]domain.Command, error)
	ListQueued(ctx context.Context, deviceID uuid.UUID) ([]domain.Command, error)
	UpdateStatus(ctx context.Context, id uuid.UUID, status domain.CommandStatus, result json.RawMessage, errMsg string) error
	MarkSent(ctx context.Context, id uuid.UUID) error
}

type MediaRepository interface {
	Create(ctx context.Context, media *domain.MediaUpload) error
	GetByID(ctx context.Context, id uuid.UUID) (*domain.MediaUpload, error)
	ListByDevice(ctx context.Context, deviceID uuid.UUID, limit int) ([]domain.MediaUpload, error)
	SoftDelete(ctx context.Context, id uuid.UUID) error
}

type WebhookRepository interface {
	CreateEndpoint(ctx context.Context, ep *domain.WebhookEndpoint) error
	ListEndpoints(ctx context.Context, ownerID uuid.UUID) ([]domain.WebhookEndpoint, error)
	ListActiveByEvent(ctx context.Context, ownerID uuid.UUID, eventType string) ([]domain.WebhookEndpoint, error)
	CreateDelivery(ctx context.Context, endpointID uuid.UUID, eventType, idempotencyKey string, payload json.RawMessage) (uuid.UUID, error)
	MarkDelivery(ctx context.Context, id uuid.UUID, status string, attempts int, lastError string) error
}

type AuditRepository interface {
	Insert(ctx context.Context, log *domain.AuditLog) error
}

type DevicePresence interface {
	SetOnline(ctx context.Context, deviceID uuid.UUID, ttl time.Duration) error
	IsOnline(ctx context.Context, deviceID uuid.UUID) (bool, error)
	Publish(ctx context.Context, channel string, payload []byte) error
	Subscribe(ctx context.Context, channel string) (<-chan []byte, func(), error)
	// CheckAndStoreNonce returns true the first time it sees this key within ttl,
	// and false on any subsequent call with the same key (replay). Used to reject
	// replayed signed agent requests.
	CheckAndStoreNonce(ctx context.Context, key string, ttl time.Duration) (fresh bool, err error)
}

type WebhookDispatcher interface {
	Dispatch(ctx context.Context, ownerID uuid.UUID, eventType string, idempotencyKey string, payload any) error
}

type Hub interface {
	Register(deviceID uuid.UUID, send func([]byte) error)
	Unregister(deviceID uuid.UUID)
	Send(deviceID uuid.UUID, payload []byte) error
	IsConnected(deviceID uuid.UUID) bool
}

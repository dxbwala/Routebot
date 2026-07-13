package postgres

import (
	"context"
	"encoding/json"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/routedns/routebot/backend/internal/domain"
)

type CommandRepo struct{ pool *pgxpool.Pool }

func NewCommandRepo(pool *pgxpool.Pool) *CommandRepo { return &CommandRepo{pool: pool} }

func (r *CommandRepo) Create(ctx context.Context, cmd *domain.Command) error {
	if cmd.Payload == nil {
		cmd.Payload = json.RawMessage(`{}`)
	}
	if cmd.Result == nil {
		cmd.Result = json.RawMessage(`{}`)
	}
	return r.pool.QueryRow(ctx, `
		INSERT INTO commands (device_id, created_by, command_type, payload, status, result)
		VALUES ($1,$2,$3,$4,$5,$6)
		RETURNING id, queued_at
	`, cmd.DeviceID, cmd.CreatedBy, cmd.CommandType, cmd.Payload, cmd.Status, cmd.Result,
	).Scan(&cmd.ID, &cmd.QueuedAt)
}

func (r *CommandRepo) GetByID(ctx context.Context, id uuid.UUID) (*domain.Command, error) {
	c := &domain.Command{}
	err := r.pool.QueryRow(ctx, `
		SELECT id, device_id, created_by, command_type, payload, status, result, error_message, queued_at, sent_at, completed_at
		FROM commands WHERE id = $1
	`, id).Scan(&c.ID, &c.DeviceID, &c.CreatedBy, &c.CommandType, &c.Payload, &c.Status, &c.Result, &c.ErrorMessage, &c.QueuedAt, &c.SentAt, &c.CompletedAt)
	return c, err
}

func (r *CommandRepo) ListByDevice(ctx context.Context, deviceID uuid.UUID, limit int) ([]domain.Command, error) {
	if limit <= 0 {
		limit = 100
	}
	rows, err := r.pool.Query(ctx, `
		SELECT id, device_id, created_by, command_type, payload, status, result, error_message, queued_at, sent_at, completed_at
		FROM commands WHERE device_id = $1 ORDER BY queued_at DESC LIMIT $2
	`, deviceID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	return scanCommands(rows)
}

func (r *CommandRepo) ListQueued(ctx context.Context, deviceID uuid.UUID) ([]domain.Command, error) {
	rows, err := r.pool.Query(ctx, `
		SELECT id, device_id, created_by, command_type, payload, status, result, error_message, queued_at, sent_at, completed_at
		FROM commands WHERE device_id = $1 AND status = 'queued' ORDER BY queued_at ASC
	`, deviceID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	return scanCommands(rows)
}

func scanCommands(rows interface {
	Next() bool
	Scan(dest ...any) error
	Err() error
}) ([]domain.Command, error) {
	var out []domain.Command
	for rows.Next() {
		var c domain.Command
		if err := rows.Scan(&c.ID, &c.DeviceID, &c.CreatedBy, &c.CommandType, &c.Payload, &c.Status, &c.Result, &c.ErrorMessage, &c.QueuedAt, &c.SentAt, &c.CompletedAt); err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

func (r *CommandRepo) UpdateStatus(ctx context.Context, id uuid.UUID, status domain.CommandStatus, result json.RawMessage, errMsg string) error {
	if result == nil {
		result = json.RawMessage(`{}`)
	}
	var completedAt *time.Time
	if status == domain.CommandSucceeded || status == domain.CommandFailed || status == domain.CommandCancelled {
		now := time.Now().UTC()
		completedAt = &now
	}
	_, err := r.pool.Exec(ctx, `
		UPDATE commands SET status = $2, result = $3, error_message = $4, completed_at = COALESCE($5, completed_at)
		WHERE id = $1
	`, id, status, result, errMsg, completedAt)
	return err
}

func (r *CommandRepo) MarkSent(ctx context.Context, id uuid.UUID) error {
	_, err := r.pool.Exec(ctx, `
		UPDATE commands SET status = 'sent', sent_at = NOW() WHERE id = $1 AND status = 'queued'
	`, id)
	return err
}

type MediaRepo struct{ pool *pgxpool.Pool }

func NewMediaRepo(pool *pgxpool.Pool) *MediaRepo { return &MediaRepo{pool: pool} }

func (r *MediaRepo) Create(ctx context.Context, m *domain.MediaUpload) error {
	return r.pool.QueryRow(ctx, `
		INSERT INTO media_uploads (device_id, command_id, media_type, storage_path, content_type, size_bytes, checksum_sha256)
		VALUES ($1,$2,$3,$4,$5,$6,$7) RETURNING id, created_at
	`, m.DeviceID, m.CommandID, m.MediaType, m.StoragePath, m.ContentType, m.SizeBytes, m.ChecksumSHA256,
	).Scan(&m.ID, &m.CreatedAt)
}

func (r *MediaRepo) GetByID(ctx context.Context, id uuid.UUID) (*domain.MediaUpload, error) {
	m := &domain.MediaUpload{}
	err := r.pool.QueryRow(ctx, `
		SELECT id, device_id, command_id, media_type, storage_path, content_type, size_bytes, checksum_sha256, created_at, deleted_at
		FROM media_uploads WHERE id = $1
	`, id).Scan(&m.ID, &m.DeviceID, &m.CommandID, &m.MediaType, &m.StoragePath, &m.ContentType, &m.SizeBytes, &m.ChecksumSHA256, &m.CreatedAt, &m.DeletedAt)
	return m, err
}

func (r *MediaRepo) ListByDevice(ctx context.Context, deviceID uuid.UUID, limit int) ([]domain.MediaUpload, error) {
	if limit <= 0 {
		limit = 50
	}
	rows, err := r.pool.Query(ctx, `
		SELECT id, device_id, command_id, media_type, storage_path, content_type, size_bytes, checksum_sha256, created_at, deleted_at
		FROM media_uploads WHERE device_id = $1 AND deleted_at IS NULL ORDER BY created_at DESC LIMIT $2
	`, deviceID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []domain.MediaUpload
	for rows.Next() {
		var m domain.MediaUpload
		if err := rows.Scan(&m.ID, &m.DeviceID, &m.CommandID, &m.MediaType, &m.StoragePath, &m.ContentType, &m.SizeBytes, &m.ChecksumSHA256, &m.CreatedAt, &m.DeletedAt); err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, rows.Err()
}

func (r *MediaRepo) SoftDelete(ctx context.Context, id uuid.UUID) error {
	_, err := r.pool.Exec(ctx, `UPDATE media_uploads SET deleted_at = NOW() WHERE id = $1`, id)
	return err
}

type WebhookRepo struct{ pool *pgxpool.Pool }

func NewWebhookRepo(pool *pgxpool.Pool) *WebhookRepo { return &WebhookRepo{pool: pool} }

func (r *WebhookRepo) CreateEndpoint(ctx context.Context, ep *domain.WebhookEndpoint) error {
	return r.pool.QueryRow(ctx, `
		INSERT INTO webhook_endpoints (owner_id, name, url, secret, event_types, is_active)
		VALUES ($1,$2,$3,$4,$5,$6) RETURNING id, created_at, updated_at
	`, ep.OwnerID, ep.Name, ep.URL, ep.Secret, ep.EventTypes, ep.IsActive,
	).Scan(&ep.ID, &ep.CreatedAt, &ep.UpdatedAt)
}

func (r *WebhookRepo) ListEndpoints(ctx context.Context, ownerID uuid.UUID) ([]domain.WebhookEndpoint, error) {
	rows, err := r.pool.Query(ctx, `
		SELECT id, owner_id, name, url, secret, event_types, is_active, created_at, updated_at
		FROM webhook_endpoints WHERE owner_id = $1 ORDER BY created_at DESC
	`, ownerID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []domain.WebhookEndpoint
	for rows.Next() {
		var ep domain.WebhookEndpoint
		if err := rows.Scan(&ep.ID, &ep.OwnerID, &ep.Name, &ep.URL, &ep.Secret, &ep.EventTypes, &ep.IsActive, &ep.CreatedAt, &ep.UpdatedAt); err != nil {
			return nil, err
		}
		out = append(out, ep)
	}
	return out, rows.Err()
}

func (r *WebhookRepo) ListActiveByEvent(ctx context.Context, ownerID uuid.UUID, eventType string) ([]domain.WebhookEndpoint, error) {
	rows, err := r.pool.Query(ctx, `
		SELECT id, owner_id, name, url, secret, event_types, is_active, created_at, updated_at
		FROM webhook_endpoints
		WHERE owner_id = $1 AND is_active = TRUE AND ($2 = ANY(event_types) OR cardinality(event_types) = 0)
	`, ownerID, eventType)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []domain.WebhookEndpoint
	for rows.Next() {
		var ep domain.WebhookEndpoint
		if err := rows.Scan(&ep.ID, &ep.OwnerID, &ep.Name, &ep.URL, &ep.Secret, &ep.EventTypes, &ep.IsActive, &ep.CreatedAt, &ep.UpdatedAt); err != nil {
			return nil, err
		}
		out = append(out, ep)
	}
	return out, rows.Err()
}

func (r *WebhookRepo) CreateDelivery(ctx context.Context, endpointID uuid.UUID, eventType, idempotencyKey string, payload json.RawMessage) (uuid.UUID, error) {
	var id uuid.UUID
	err := r.pool.QueryRow(ctx, `
		INSERT INTO webhook_deliveries (endpoint_id, event_type, idempotency_key, payload)
		VALUES ($1,$2,$3,$4)
		ON CONFLICT (endpoint_id, idempotency_key) DO UPDATE SET endpoint_id = EXCLUDED.endpoint_id
		RETURNING id
	`, endpointID, eventType, idempotencyKey, payload).Scan(&id)
	return id, err
}

func (r *WebhookRepo) MarkDelivery(ctx context.Context, id uuid.UUID, status string, attempts int, lastError string) error {
	_, err := r.pool.Exec(ctx, `
		UPDATE webhook_deliveries
		SET status = $2, attempts = $3, last_error = $4,
		    delivered_at = CASE WHEN $2 = 'success' THEN NOW() ELSE delivered_at END
		WHERE id = $1
	`, id, status, attempts, lastError)
	return err
}

type AuditRepo struct{ pool *pgxpool.Pool }

func NewAuditRepo(pool *pgxpool.Pool) *AuditRepo { return &AuditRepo{pool: pool} }

func (r *AuditRepo) Insert(ctx context.Context, log *domain.AuditLog) error {
	if log.Metadata == nil {
		log.Metadata = json.RawMessage(`{}`)
	}
	return r.pool.QueryRow(ctx, `
		INSERT INTO audit_logs (actor_type, actor_id, action, resource_type, resource_id, metadata, ip_address)
		VALUES ($1,$2,$3,$4,$5,$6,$7) RETURNING id, created_at
	`, log.ActorType, log.ActorID, log.Action, log.ResourceType, log.ResourceID, log.Metadata, log.IPAddress,
	).Scan(&log.ID, &log.CreatedAt)
}

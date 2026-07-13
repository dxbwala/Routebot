package postgres

import (
	"context"
	"encoding/json"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/routedns/routebot/backend/internal/domain"
)

type SMSRepo struct{ pool *pgxpool.Pool }

func NewSMSRepo(pool *pgxpool.Pool) *SMSRepo { return &SMSRepo{pool: pool} }

func (r *SMSRepo) Create(ctx context.Context, msg *domain.SMSMessage) error {
	return r.pool.QueryRow(ctx, `
		INSERT INTO sms_messages (device_id, direction, address, body, sim_slot, status, provider_ref, delivered_at)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8)
		RETURNING id, created_at
	`, msg.DeviceID, msg.Direction, msg.Address, msg.Body, msg.SIMSlot, msg.Status, msg.ProviderRef, msg.DeliveredAt,
	).Scan(&msg.ID, &msg.CreatedAt)
}

func (r *SMSRepo) ListByDevice(ctx context.Context, deviceID uuid.UUID, limit int) ([]domain.SMSMessage, error) {
	if limit <= 0 {
		limit = 100
	}
	rows, err := r.pool.Query(ctx, `
		SELECT id, device_id, direction, address, body, sim_slot, status, provider_ref, delivered_at, created_at
		FROM sms_messages WHERE device_id = $1 ORDER BY created_at DESC LIMIT $2
	`, deviceID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []domain.SMSMessage
	for rows.Next() {
		var m domain.SMSMessage
		if err := rows.Scan(&m.ID, &m.DeviceID, &m.Direction, &m.Address, &m.Body, &m.SIMSlot, &m.Status, &m.ProviderRef, &m.DeliveredAt, &m.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, rows.Err()
}

func (r *SMSRepo) UpdateStatus(ctx context.Context, id uuid.UUID, deviceID uuid.UUID, status string, deliveredAt *time.Time) (bool, error) {
	tag, err := r.pool.Exec(ctx, `
		UPDATE sms_messages SET status = $3, delivered_at = COALESCE($4, delivered_at)
		WHERE id = $1 AND device_id = $2
	`, id, deviceID, status, deliveredAt)
	if err != nil {
		return false, err
	}
	return tag.RowsAffected() > 0, nil
}

type OTPRepo struct{ pool *pgxpool.Pool }

func NewOTPRepo(pool *pgxpool.Pool) *OTPRepo { return &OTPRepo{pool: pool} }

func (r *OTPRepo) Create(ctx context.Context, evt *domain.OTPEvent) error {
	return r.pool.QueryRow(ctx, `
		INSERT INTO otp_events (device_id, source, sender, otp_code, raw_text, pattern)
		VALUES ($1,$2,$3,$4,$5,$6) RETURNING id, created_at
	`, evt.DeviceID, evt.Source, evt.Sender, evt.OTPCode, evt.RawText, evt.Pattern,
	).Scan(&evt.ID, &evt.CreatedAt)
}

func (r *OTPRepo) ListByDevice(ctx context.Context, deviceID uuid.UUID, limit int) ([]domain.OTPEvent, error) {
	if limit <= 0 {
		limit = 100
	}
	rows, err := r.pool.Query(ctx, `
		SELECT id, device_id, source, sender, otp_code, raw_text, pattern, created_at
		FROM otp_events WHERE device_id = $1 ORDER BY created_at DESC LIMIT $2
	`, deviceID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []domain.OTPEvent
	for rows.Next() {
		var e domain.OTPEvent
		if err := rows.Scan(&e.ID, &e.DeviceID, &e.Source, &e.Sender, &e.OTPCode, &e.RawText, &e.Pattern, &e.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, e)
	}
	return out, rows.Err()
}

type NotificationRepo struct{ pool *pgxpool.Pool }

func NewNotificationRepo(pool *pgxpool.Pool) *NotificationRepo {
	return &NotificationRepo{pool: pool}
}

func (r *NotificationRepo) Create(ctx context.Context, evt *domain.NotificationEvent) error {
	if evt.Payload == nil {
		evt.Payload = json.RawMessage(`{}`)
	}
	return r.pool.QueryRow(ctx, `
		INSERT INTO notification_events (device_id, package_name, title, text, payload, posted_at)
		VALUES ($1,$2,$3,$4,$5,$6) RETURNING id, created_at
	`, evt.DeviceID, evt.PackageName, evt.Title, evt.Text, evt.Payload, evt.PostedAt,
	).Scan(&evt.ID, &evt.CreatedAt)
}

func (r *NotificationRepo) ListByDevice(ctx context.Context, deviceID uuid.UUID, limit int) ([]domain.NotificationEvent, error) {
	if limit <= 0 {
		limit = 100
	}
	rows, err := r.pool.Query(ctx, `
		SELECT id, device_id, package_name, title, text, payload, posted_at, created_at
		FROM notification_events WHERE device_id = $1 ORDER BY created_at DESC LIMIT $2
	`, deviceID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []domain.NotificationEvent
	for rows.Next() {
		var e domain.NotificationEvent
		if err := rows.Scan(&e.ID, &e.DeviceID, &e.PackageName, &e.Title, &e.Text, &e.Payload, &e.PostedAt, &e.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, e)
	}
	return out, rows.Err()
}

type CallRepo struct{ pool *pgxpool.Pool }

func NewCallRepo(pool *pgxpool.Pool) *CallRepo { return &CallRepo{pool: pool} }

func (r *CallRepo) Create(ctx context.Context, evt *domain.CallEvent) error {
	return r.pool.QueryRow(ctx, `
		INSERT INTO call_events (device_id, call_type, number, state, duration_sec, started_at)
		VALUES ($1,$2,$3,$4,$5,$6) RETURNING id, created_at
	`, evt.DeviceID, evt.CallType, evt.Number, evt.State, evt.DurationSec, evt.StartedAt,
	).Scan(&evt.ID, &evt.CreatedAt)
}

func (r *CallRepo) ListByDevice(ctx context.Context, deviceID uuid.UUID, limit int) ([]domain.CallEvent, error) {
	if limit <= 0 {
		limit = 100
	}
	rows, err := r.pool.Query(ctx, `
		SELECT id, device_id, call_type, number, state, duration_sec, started_at, created_at
		FROM call_events WHERE device_id = $1 ORDER BY created_at DESC LIMIT $2
	`, deviceID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []domain.CallEvent
	for rows.Next() {
		var e domain.CallEvent
		if err := rows.Scan(&e.ID, &e.DeviceID, &e.CallType, &e.Number, &e.State, &e.DurationSec, &e.StartedAt, &e.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, e)
	}
	return out, rows.Err()
}

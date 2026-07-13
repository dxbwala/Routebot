package postgres

import (
	"context"
	"encoding/json"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/routedns/routebot/backend/internal/domain"
)

type DeviceRepo struct{ pool *pgxpool.Pool }

func NewDeviceRepo(pool *pgxpool.Pool) *DeviceRepo { return &DeviceRepo{pool: pool} }

func (r *DeviceRepo) Create(ctx context.Context, d *domain.Device) error {
	if d.Metadata == nil {
		d.Metadata = json.RawMessage(`{}`)
	}
	return r.pool.QueryRow(ctx, `
		INSERT INTO devices (
			owner_id, device_uuid, name, api_key_hash, api_key_enc, api_key_prefix, status,
			manufacturer, model, android_version, app_version, metadata
		) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12)
		RETURNING id, created_at, updated_at
	`, d.OwnerID, d.DeviceUUID, d.Name, d.APIKeyHash, d.APIKeyEnc, d.APIKeyPrefix, d.Status,
		d.Manufacturer, d.Model, d.AndroidVersion, d.AppVersion, d.Metadata,
	).Scan(&d.ID, &d.CreatedAt, &d.UpdatedAt)
}

func scanDevice(row interface {
	Scan(dest ...any) error
}) (*domain.Device, error) {
	d := &domain.Device{}
	err := row.Scan(
		&d.ID, &d.OwnerID, &d.DeviceUUID, &d.Name, &d.APIKeyHash, &d.APIKeyEnc, &d.APIKeyPrefix,
		&d.Status, &d.Manufacturer, &d.Model, &d.AndroidVersion, &d.AppVersion,
		&d.LastSeenAt, &d.Metadata, &d.CreatedAt, &d.UpdatedAt,
	)
	return d, err
}

const deviceCols = `
	id, owner_id, device_uuid, name, api_key_hash, api_key_enc, api_key_prefix, status,
	manufacturer, model, android_version, app_version, last_seen_at, metadata, created_at, updated_at
`

func (r *DeviceRepo) GetByID(ctx context.Context, id uuid.UUID) (*domain.Device, error) {
	return scanDevice(r.pool.QueryRow(ctx, `SELECT `+deviceCols+` FROM devices WHERE id = $1`, id))
}

func (r *DeviceRepo) GetByUUID(ctx context.Context, deviceUUID string) (*domain.Device, error) {
	return scanDevice(r.pool.QueryRow(ctx, `SELECT `+deviceCols+` FROM devices WHERE device_uuid = $1`, deviceUUID))
}

func (r *DeviceRepo) GetByAPIKeyHash(ctx context.Context, hash string) (*domain.Device, error) {
	return scanDevice(r.pool.QueryRow(ctx, `SELECT `+deviceCols+` FROM devices WHERE api_key_hash = $1`, hash))
}

func (r *DeviceRepo) ListByOwner(ctx context.Context, ownerID uuid.UUID) ([]domain.Device, error) {
	rows, err := r.pool.Query(ctx, `SELECT `+deviceCols+` FROM devices WHERE owner_id = $1 ORDER BY created_at DESC`, ownerID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []domain.Device
	for rows.Next() {
		d, err := scanDevice(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, *d)
	}
	return out, rows.Err()
}

func (r *DeviceRepo) UpdateProfile(ctx context.Context, d *domain.Device) error {
	_, err := r.pool.Exec(ctx, `
		UPDATE devices SET
			name = $2, manufacturer = $3, model = $4, android_version = $5,
			app_version = $6, metadata = $7, updated_at = NOW()
		WHERE id = $1
	`, d.ID, d.Name, d.Manufacturer, d.Model, d.AndroidVersion, d.AppVersion, d.Metadata)
	return err
}

func (r *DeviceRepo) UpdateStatus(ctx context.Context, id uuid.UUID, status domain.DeviceStatus, lastSeen *time.Time) error {
	_, err := r.pool.Exec(ctx, `
		UPDATE devices SET status = $2, last_seen_at = COALESCE($3, last_seen_at), updated_at = NOW()
		WHERE id = $1
	`, id, status, lastSeen)
	return err
}

func (r *DeviceRepo) MarkStaleOffline(ctx context.Context, threshold time.Time) (int64, error) {
	tag, err := r.pool.Exec(ctx, `
		UPDATE devices SET status = 'offline', updated_at = NOW()
		WHERE status = 'online' AND (last_seen_at IS NULL OR last_seen_at < $1)
	`, threshold)
	if err != nil {
		return 0, err
	}
	return tag.RowsAffected(), nil
}

type HeartbeatRepo struct{ pool *pgxpool.Pool }

func NewHeartbeatRepo(pool *pgxpool.Pool) *HeartbeatRepo { return &HeartbeatRepo{pool: pool} }

func (r *HeartbeatRepo) Insert(ctx context.Context, hb *domain.DeviceHeartbeat) error {
	if hb.SIMInfo == nil {
		hb.SIMInfo = json.RawMessage(`[]`)
	}
	if hb.Payload == nil {
		hb.Payload = json.RawMessage(`{}`)
	}
	return r.pool.QueryRow(ctx, `
		INSERT INTO device_heartbeats (
			device_id, battery_level, is_charging, storage_free_mb, ram_free_mb, cpu_usage,
			network_type, wifi_ssid, signal_strength, sim_info, payload
		) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11)
		RETURNING id, reported_at
	`, hb.DeviceID, hb.BatteryLevel, hb.IsCharging, hb.StorageFreeMB, hb.RAMFreeMB, hb.CPUUsage,
		hb.NetworkType, hb.WifiSSID, hb.SignalStrength, hb.SIMInfo, hb.Payload,
	).Scan(&hb.ID, &hb.ReportedAt)
}

func (r *HeartbeatRepo) LatestByDevice(ctx context.Context, deviceID uuid.UUID) (*domain.DeviceHeartbeat, error) {
	hb := &domain.DeviceHeartbeat{}
	err := r.pool.QueryRow(ctx, `
		SELECT id, device_id, battery_level, is_charging, storage_free_mb, ram_free_mb, cpu_usage,
			network_type, wifi_ssid, signal_strength, sim_info, payload, reported_at
		FROM device_heartbeats WHERE device_id = $1 ORDER BY reported_at DESC LIMIT 1
	`, deviceID).Scan(
		&hb.ID, &hb.DeviceID, &hb.BatteryLevel, &hb.IsCharging, &hb.StorageFreeMB, &hb.RAMFreeMB, &hb.CPUUsage,
		&hb.NetworkType, &hb.WifiSSID, &hb.SignalStrength, &hb.SIMInfo, &hb.Payload, &hb.ReportedAt,
	)
	return hb, err
}

func (r *HeartbeatRepo) ListByDevice(ctx context.Context, deviceID uuid.UUID, limit int) ([]domain.DeviceHeartbeat, error) {
	if limit <= 0 {
		limit = 50
	}
	rows, err := r.pool.Query(ctx, `
		SELECT id, device_id, battery_level, is_charging, storage_free_mb, ram_free_mb, cpu_usage,
			network_type, wifi_ssid, signal_strength, sim_info, payload, reported_at
		FROM device_heartbeats WHERE device_id = $1 ORDER BY reported_at DESC LIMIT $2
	`, deviceID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []domain.DeviceHeartbeat
	for rows.Next() {
		var hb domain.DeviceHeartbeat
		if err := rows.Scan(
			&hb.ID, &hb.DeviceID, &hb.BatteryLevel, &hb.IsCharging, &hb.StorageFreeMB, &hb.RAMFreeMB, &hb.CPUUsage,
			&hb.NetworkType, &hb.WifiSSID, &hb.SignalStrength, &hb.SIMInfo, &hb.Payload, &hb.ReportedAt,
		); err != nil {
			return nil, err
		}
		out = append(out, hb)
	}
	return out, rows.Err()
}

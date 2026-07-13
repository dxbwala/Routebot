package ws

import (
	"sync"

	"github.com/google/uuid"
)

// Hub manages in-memory device WebSocket connections.
type Hub struct {
	mu   sync.RWMutex
	conn map[uuid.UUID]func([]byte) error
}

func NewHub() *Hub {
	return &Hub{conn: make(map[uuid.UUID]func([]byte) error)}
}

func (h *Hub) Register(deviceID uuid.UUID, send func([]byte) error) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.conn[deviceID] = send
}

func (h *Hub) Unregister(deviceID uuid.UUID) {
	h.mu.Lock()
	defer h.mu.Unlock()
	delete(h.conn, deviceID)
}

func (h *Hub) Send(deviceID uuid.UUID, payload []byte) error {
	h.mu.RLock()
	send, ok := h.conn[deviceID]
	h.mu.RUnlock()
	if !ok {
		return ErrNotConnected
	}
	return send(payload)
}

func (h *Hub) IsConnected(deviceID uuid.UUID) bool {
	h.mu.RLock()
	defer h.mu.RUnlock()
	_, ok := h.conn[deviceID]
	return ok
}

var ErrNotConnected = &notConnectedError{}

type notConnectedError struct{}

func (e *notConnectedError) Error() string { return "device not connected" }

package webhook

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"time"

	"github.com/google/uuid"
	"github.com/routedns/routebot/backend/internal/pkg/crypto"
	"github.com/routedns/routebot/backend/internal/pkg/logger"
	"github.com/routedns/routebot/backend/internal/ports"
)

type Dispatcher struct {
	repo   ports.WebhookRepository
	client *http.Client
	log    *logger.Logger
}

func NewDispatcher(repo ports.WebhookRepository, log *logger.Logger) *Dispatcher {
	return &Dispatcher{
		repo: repo,
		client: &http.Client{
			Timeout: 10 * time.Second,
		},
		log: log,
	}
}

type envelope struct {
	ID        string          `json:"id"`
	Event     string          `json:"event"`
	Timestamp int64           `json:"timestamp"`
	Data      json.RawMessage `json:"data"`
}

func (d *Dispatcher) Dispatch(ctx context.Context, ownerID uuid.UUID, eventType, idempotencyKey string, payload any) error {
	body, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	endpoints, err := d.repo.ListActiveByEvent(ctx, ownerID, eventType)
	if err != nil {
		return err
	}
	for _, ep := range endpoints {
		deliveryID, err := d.repo.CreateDelivery(ctx, ep.ID, eventType, idempotencyKey, body)
		if err != nil {
			d.log.Error("webhook delivery create failed", map[string]any{"error": err.Error()})
			continue
		}
		go d.deliver(context.Background(), ep.URL, ep.Secret, deliveryID, eventType, body)
	}
	return nil
}

func (d *Dispatcher) deliver(ctx context.Context, url, secret string, deliveryID uuid.UUID, eventType string, data []byte) {
	ts := time.Now().UTC()
	env := envelope{
		ID:        deliveryID.String(),
		Event:     eventType,
		Timestamp: ts.Unix(),
		Data:      data,
	}
	raw, err := json.Marshal(env)
	if err != nil {
		_ = d.repo.MarkDelivery(ctx, deliveryID, "failed", 1, err.Error())
		return
	}
	sig := crypto.SignWebhook(secret, ts, raw)

	var lastErr string
	attempts := 0
	for attempt := 1; attempt <= 5; attempt++ {
		attempts = attempt
		req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(raw))
		if err != nil {
			lastErr = err.Error()
			break
		}
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-RouteBot-Timestamp", fmt.Sprintf("%d", ts.Unix()))
		req.Header.Set("X-RouteBot-Signature", sig)
		req.Header.Set("X-RouteBot-Event", eventType)
		req.Header.Set("X-RouteBot-Delivery", deliveryID.String())

		resp, err := d.client.Do(req)
		if err != nil {
			lastErr = err.Error()
		} else {
			_ = resp.Body.Close()
			if resp.StatusCode >= 200 && resp.StatusCode < 300 {
				_ = d.repo.MarkDelivery(ctx, deliveryID, "success", attempts, "")
				return
			}
			lastErr = fmt.Sprintf("status %d", resp.StatusCode)
		}
		time.Sleep(time.Duration(1<<uint(attempt-1)) * time.Second)
	}
	_ = d.repo.MarkDelivery(ctx, deliveryID, "failed", attempts, lastErr)
}

package redis

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
	goredis "github.com/redis/go-redis/v9"
)

type Presence struct {
	client *goredis.Client
}

func NewClient(addr, password string, db int) *goredis.Client {
	return goredis.NewClient(&goredis.Options{
		Addr:     addr,
		Password: password,
		DB:       db,
	})
}

func NewPresence(client *goredis.Client) *Presence {
	return &Presence{client: client}
}

func (p *Presence) SetOnline(ctx context.Context, deviceID uuid.UUID, ttl time.Duration) error {
	return p.client.Set(ctx, presenceKey(deviceID), "1", ttl).Err()
}

func (p *Presence) IsOnline(ctx context.Context, deviceID uuid.UUID) (bool, error) {
	n, err := p.client.Exists(ctx, presenceKey(deviceID)).Result()
	return n > 0, err
}

func (p *Presence) Publish(ctx context.Context, channel string, payload []byte) error {
	return p.client.Publish(ctx, channel, payload).Err()
}

func (p *Presence) Subscribe(ctx context.Context, channel string) (<-chan []byte, func(), error) {
	sub := p.client.Subscribe(ctx, channel)
	ch := make(chan []byte, 64)
	go func() {
		defer close(ch)
		for msg := range sub.Channel() {
			select {
			case ch <- []byte(msg.Payload):
			case <-ctx.Done():
				return
			}
		}
	}()
	cancel := func() { _ = sub.Close() }
	return ch, cancel, nil
}

func presenceKey(deviceID uuid.UUID) string {
	return fmt.Sprintf("device:online:%s", deviceID.String())
}

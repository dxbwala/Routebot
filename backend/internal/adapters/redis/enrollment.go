package redis

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/google/uuid"
	goredis "github.com/redis/go-redis/v9"
)

type EnrollmentRecord struct {
	OwnerID    uuid.UUID `json:"owner_id"`
	DeviceName string    `json:"device_name"`
}

type EnrollmentStore struct {
	client *goredis.Client
}

func NewEnrollmentStore(client *goredis.Client) *EnrollmentStore {
	return &EnrollmentStore{client: client}
}

func (s *EnrollmentStore) Put(ctx context.Context, token string, ownerID uuid.UUID, deviceName string, ttl time.Duration) error {
	rec := EnrollmentRecord{OwnerID: ownerID, DeviceName: deviceName}
	b, err := json.Marshal(rec)
	if err != nil {
		return err
	}
	return s.client.Set(ctx, enrollmentKey(token), b, ttl).Err()
}

func (s *EnrollmentStore) Take(ctx context.Context, token string) (uuid.UUID, string, error) {
	key := enrollmentKey(token)
	b, err := s.client.GetDel(ctx, key).Bytes()
	if err == goredis.Nil {
		return uuid.Nil, "", fmt.Errorf("not found")
	}
	if err != nil {
		return uuid.Nil, "", err
	}
	var rec EnrollmentRecord
	if err := json.Unmarshal(b, &rec); err != nil {
		return uuid.Nil, "", err
	}
	return rec.OwnerID, rec.DeviceName, nil
}

func enrollmentKey(token string) string {
	return fmt.Sprintf("enrollment:%s", token)
}

package crypto

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"strconv"
	"time"
)

// SignWebhook creates an HMAC-SHA256 signature over timestamp + body.
func SignWebhook(secret string, timestamp time.Time, body []byte) string {
	mac := hmac.New(sha256.New, []byte(secret))
	_, _ = mac.Write([]byte(strconv.FormatInt(timestamp.Unix(), 10)))
	_, _ = mac.Write([]byte("."))
	_, _ = mac.Write(body)
	return hex.EncodeToString(mac.Sum(nil))
}

// VerifyWebhook validates signature and rejects stale timestamps (replay protection).
func VerifyWebhook(secret string, timestampUnix int64, body []byte, signature string, maxSkew time.Duration) error {
	now := time.Now().Unix()
	if abs(now-timestampUnix) > int64(maxSkew.Seconds()) {
		return fmt.Errorf("timestamp outside allowed skew")
	}
	expected := SignWebhook(secret, time.Unix(timestampUnix, 0).UTC(), body)
	if !hmac.Equal([]byte(expected), []byte(signature)) {
		return fmt.Errorf("invalid signature")
	}
	return nil
}

func abs(v int64) int64 {
	if v < 0 {
		return -v
	}
	return v
}

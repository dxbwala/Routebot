package auth_test

import (
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/routedns/routebot/backend/internal/pkg/auth"
	"github.com/routedns/routebot/backend/internal/pkg/crypto"
	"github.com/stretchr/testify/require"
)

func TestPasswordAndAPIKey(t *testing.T) {
	hash, err := auth.HashPassword("secretpass")
	require.NoError(t, err)
	require.True(t, auth.CheckPassword(hash, "secretpass"))
	require.False(t, auth.CheckPassword(hash, "wrong"))

	raw, prefix, err := auth.GenerateAPIKey()
	require.NoError(t, err)
	require.Contains(t, raw, "rb_")
	require.Equal(t, raw[:12], prefix)
	h1 := auth.HashAPIKey(raw, "pepper")
	h2 := auth.HashAPIKey(raw, "pepper")
	require.Equal(t, h1, h2)
}

func TestJWT(t *testing.T) {
	m := auth.NewManager("01234567890123456789012345678901", time.Minute, time.Hour)
	uid := uuid.New()
	pair, refreshHash, err := m.Issue(uid, "a@b.com", "admin")
	require.NoError(t, err)
	require.NotEmpty(t, pair.AccessToken)
	require.NotEmpty(t, refreshHash)
	claims, err := m.Parse(pair.AccessToken)
	require.NoError(t, err)
	require.Equal(t, uid, claims.UserID)
}

func TestWebhookHMAC(t *testing.T) {
	ts := time.Unix(1700000000, 0).UTC()
	body := []byte(`{"ok":true}`)
	sig := crypto.SignWebhook("secret", ts, body)
	require.NoError(t, crypto.VerifyWebhook("secret", ts.Unix(), body, sig, time.Hour*24*365*10))
	require.Error(t, crypto.VerifyWebhook("secret", ts.Unix(), body, "bad", time.Hour*24*365*10))
}

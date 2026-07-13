-- Enables agent request signing: store an encrypted copy of the raw device API key
-- (separate from the one-way hash used for authentication) so the server can
-- recompute the HMAC signature the device sends on every agent request.
ALTER TABLE devices ADD COLUMN IF NOT EXISTS api_key_enc TEXT NOT NULL DEFAULT '';

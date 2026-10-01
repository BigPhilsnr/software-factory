-- Existing rows keep their keyless SHA-256 hashes; new rows record the keyed HMAC scheme explicitly.
ALTER TABLE audit_events ADD COLUMN IF NOT EXISTS hash_scheme SMALLINT NOT NULL DEFAULT 1;
CREATE INDEX IF NOT EXISTS runs_updated_at_idx ON runs (updated_at DESC);
CREATE INDEX IF NOT EXISTS chat_audit_at_idx ON chat_audit (at);

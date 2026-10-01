-- Additive adoption of the existing control schema: preserve state and audit bytes.
CREATE TABLE IF NOT EXISTS runs (id UUID PRIMARY KEY, state_json TEXT NOT NULL, updated_at TIMESTAMPTZ NOT NULL);
ALTER TABLE runs ADD COLUMN IF NOT EXISTS revision BIGINT NOT NULL DEFAULT 0;
CREATE TABLE IF NOT EXISTS audit_events (run_id UUID NOT NULL REFERENCES runs(id), seq BIGINT NOT NULL, at TIMESTAMPTZ NOT NULL, type TEXT NOT NULL, detail TEXT NOT NULL, previous_hash CHAR(64) NOT NULL, event_hash CHAR(64) NOT NULL, PRIMARY KEY(run_id, seq));
ALTER TABLE audit_events ADD COLUMN IF NOT EXISTS state_json TEXT;
CREATE TABLE IF NOT EXISTS chat_budget (day DATE PRIMARY KEY, requests INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS chat_audit (id UUID PRIMARY KEY, at TIMESTAMPTZ NOT NULL, type TEXT NOT NULL, detail TEXT NOT NULL);

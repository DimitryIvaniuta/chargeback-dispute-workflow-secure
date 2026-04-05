-- Adds PII envelope kid column and operational re-encryption job tracking.

ALTER TABLE dispute_cases
  ADD COLUMN IF NOT EXISTS pii_kid VARCHAR(64) NULL;

CREATE INDEX IF NOT EXISTS ix_dispute_cases_pii_kid ON dispute_cases(pii_kid);

CREATE TABLE IF NOT EXISTS dispute_pii_reencrypt_jobs (
  id UUID PRIMARY KEY,
  old_kid VARCHAR(64) NOT NULL,
  new_kid VARCHAR(64) NOT NULL,
  status VARCHAR(24) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  started_at TIMESTAMPTZ NULL,
  finished_at TIMESTAMPTZ NULL,
  requested_by VARCHAR(120) NOT NULL,
  batch_size INT NOT NULL,
  delay_ms INT NOT NULL,
  total BIGINT NOT NULL DEFAULT 0,
  processed BIGINT NOT NULL DEFAULT 0,
  failures BIGINT NOT NULL DEFAULT 0,
  last_error TEXT NULL,
  cancel_requested BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS ix_pii_reencrypt_jobs_status ON dispute_pii_reencrypt_jobs(status);

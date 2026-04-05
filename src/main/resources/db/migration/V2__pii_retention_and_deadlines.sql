-- Adds encrypted PII envelope and retention/auditor supporting fields.

ALTER TABLE dispute_cases
  ADD COLUMN IF NOT EXISTS pii_envelope TEXT NULL,
  ADD COLUMN IF NOT EXISTS closed_at TIMESTAMPTZ NULL,
  ADD COLUMN IF NOT EXISTS legal_hold BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS deadline_breached BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS ix_dispute_cases_closed_at ON dispute_cases(closed_at);

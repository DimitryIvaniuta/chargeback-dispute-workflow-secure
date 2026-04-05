CREATE TABLE dispute_cases (
  id                 UUID PRIMARY KEY,
  version            BIGINT NOT NULL,
  external_ref       VARCHAR(120) NOT NULL,
  customer_ref_hash  CHAR(64) NOT NULL,
  amount_cents       BIGINT NOT NULL,
  currency           VARCHAR(3) NOT NULL,
  state              VARCHAR(40) NOT NULL,
  assigned_team      VARCHAR(40) NOT NULL,
  opened_at          TIMESTAMPTZ NOT NULL,
  due_at             TIMESTAMPTZ NULL,
  last_updated_at    TIMESTAMPTZ NOT NULL,
  last_updated_by    VARCHAR(120) NOT NULL,
  description        VARCHAR(2000) NULL
);

CREATE UNIQUE INDEX ux_dispute_cases_external_ref ON dispute_cases(external_ref);
CREATE INDEX ix_dispute_cases_team_state ON dispute_cases(assigned_team, state);
CREATE INDEX ix_dispute_cases_due_at ON dispute_cases(due_at);

CREATE TABLE dispute_attachments (
  id            UUID PRIMARY KEY,
  case_id       UUID NOT NULL REFERENCES dispute_cases(id),
  storage_key   VARCHAR(512) NOT NULL,
  filename      VARCHAR(255) NOT NULL,
  content_type  VARCHAR(120) NOT NULL,
  size_bytes    BIGINT NOT NULL,
  sha256        CHAR(64) NOT NULL,
  uploaded_at   TIMESTAMPTZ NOT NULL,
  uploaded_by   VARCHAR(120) NOT NULL
);

CREATE INDEX ix_dispute_attachments_case_id ON dispute_attachments(case_id);

CREATE TABLE dispute_audit_log (
  id              UUID PRIMARY KEY,
  case_id          UUID NOT NULL REFERENCES dispute_cases(id),
  occurred_at      TIMESTAMPTZ NOT NULL,
  actor            VARCHAR(120) NOT NULL,
  action           VARCHAR(80) NOT NULL,
  correlation_id   VARCHAR(64) NULL,
  before_json      TEXT NULL,
  after_json       TEXT NULL,
  details          VARCHAR(2000) NULL
);

CREATE INDEX ix_dispute_audit_case_id ON dispute_audit_log(case_id);
CREATE INDEX ix_dispute_audit_occurred_at ON dispute_audit_log(occurred_at);

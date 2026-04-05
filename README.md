# Chargeback / Dispute Workflow (secure case management)

Production-grade Spring Boot service for managing chargebacks/disputes with:

- **Case state machine** + **deadlines (SLA)**
- **RBAC + team-based access control**
- **Attachment metadata only** (binary stored in external secure storage; DB stores metadata + storageKey)
- **Immutable audit trail** for every change (append-only audit table)
- **Kafka events** on key changes (state/team/attachment/deadline breach)
- **Redis cache** for case reads (evicted on writes)

## Tech stack

- Java 21
- Spring Boot 3.5.10 (Web, Validation, Security, OAuth2 Resource Server, Data JPA, Cache, Redis, Actuator)
- PostgreSQL + Flyway
- Apache Kafka (KRaft) for events
- Redis for caching
- Gradle (Groovy)
- Testcontainers + JUnit 5

## Security model (RBAC + team)

JWT must include claims:

- `sub`: user id
- `team`: `CHARGEBACK` | `FRAUD` | `SUPPORT`
- `roles`: list, e.g. `["DISPUTE_VIEW","DISPUTE_EDIT"]`

Authorities are created as:

- `ROLE_<role>` (e.g. `ROLE_DISPUTE_EDIT`)
- `TEAM_<team>` (e.g. `TEAM_CHARGEBACK`)

Access rules:

- `ROLE_DISPUTE_ADMIN` → can access any case and can re-assign team
- otherwise user must have matching `TEAM_<case.assignedTeam>`
- edit endpoints require `ROLE_DISPUTE_EDIT` (or admin)

### Demo token issuer

For local testing only, the service exposes:

`POST /api/auth/token`

This issues a 60-min token using the configured HMAC secret (`app.security.jwt.secret`).
**Disable** in production via:

```yaml
app:
  security:
    token-issuer:
      enabled: false
```

## Running locally

### 1) Start infrastructure

```bash
docker compose up -d
```

### 2) Run the service

```bash
./gradlew bootRun
```

Service listens on `http://localhost:8080`.

### 3) Use Postman collection

Import `postman/Chargeback_Dispute_Workflow.postman_collection.json`.

Workflow:

1. Call **Issue Token** to get a JWT
2. Use JWT in `Authorization: Bearer <token>` for other requests
3. Create a case → change state → register attachment metadata → view audit

## Database schema

Managed by Flyway:

- `dispute_cases` – case aggregate (no raw PII, only `customer_ref_hash`)
- `dispute_attachments` – attachment metadata only
- `dispute_audit_log` – immutable audit records (append-only)

## Kafka

Events published to topic: `dispute-events`

Event payloads:
- `DisputeEvent` (type = CASE_OPENED/STATE_CHANGED/TEAM_ASSIGNED/ATTACHMENT_REGISTERED/DEADLINE_BREACHED)

## Tests

```bash
./gradlew test
```

Integration tests use **Testcontainers** for PostgreSQL and mock KafkaTemplate (so no Kafka is required to run tests).

## GDPR notes (practical defaults)

- Do not store raw customer email/PII in DB by default.
- Store hashes (`customer_ref_hash`) to correlate without exposing raw values.
- If you must store PII, encrypt at rest and implement retention/redaction policies.

---


## Extensions: Encrypted PII, Auditor Export, Presigned Attachments

### 1) Encrypted PII (application-layer envelope encryption)

- Optional PII fields: `email`, `fullName`, `phone`
- Stored in DB as `dispute_cases.pii_envelope` (Base64 JSON) using AES/GCM envelope encryption.
- Decrypted PII is **only returned** when the caller has `ROLE_DISPUTE_PII_VIEW` (or `ROLE_DISPUTE_ADMIN`).
- Audit snapshots never contain the encrypted blob (and never contain decrypted PII).

Config (local/dev):
```yaml
app:
  pii:
    master-key-base64: "<base64-32-bytes>"
    retention-after-close: "PT2160H" # 90 days
```

> Alternative (DB-side): PostgreSQL `pgcrypto` can be used for `pgp_sym_encrypt/pgp_sym_decrypt`.
> This repo implements application-layer envelope encryption to avoid storing DB secrets.

### 2) Retention + export tooling for auditors

- Retention job runs every 15 minutes:
  - When `closedAt` is set and `legalHold=false`, PII is purged after `app.pii.retention-after-close`
  - Audit action recorded: `PII_PURGED`
- Auditor CSV exports (generated on-demand; not persisted):
  - `GET /api/audit/cases.csv` (optional filters + `includePii=true|false`)
  - `GET /api/audit/audit-log.csv`
  - `GET /api/audit/cases/{caseId}/audit.csv`

Roles:
- `ROLE_DISPUTE_AUDITOR` (read-only exports across teams)
- `ROLE_DISPUTE_PII_VIEW` (allows decrypted PII visibility)

### 3) Presigned upload/download endpoints for attachments

Preferred flow (metadata + presign):
- `POST /api/cases/{id}/attachments/presign-upload` → returns `uploadUrl` + stores metadata
- `GET /api/cases/{id}/attachments/{attachmentId}/presign-download` → returns `downloadUrl`

Storage provider:
- Default is a local dummy service (returns deterministic URLs).
- Enable real S3 presigner with:
```yaml
app:
  storage:
    s3:
      enabled: true
      bucket: "disputes-secure"
      region: "eu-central-1"
      endpoint: "http://localhost:4566" # optional (LocalStack)
      presign-expiry: "PT15M"
```


## Key rotation operational tooling

Admin-only endpoints (require `ROLE_DISPUTE_ADMIN`):

- `GET /api/admin/crypto/pii-keys` - list configured PII envelope master keys (no key material)
- `GET /api/admin/crypto/pii-keys/health` - validate ring health
- `POST /api/admin/crypto/pii-keys/{keyId}/promote` - switch primary key used for new encryption (runtime only)
- `POST /api/admin/crypto/pii-keys/generate` - generate new AES-256 key material (Base64) for configuration/secret storage

Export signing keys:

- `GET /api/admin/crypto/export-signing` - current public key metadata
- `POST /api/admin/crypto/export-signing/generate` - generate new Ed25519 keypair (returns PKCS#8 private + X.509 public Base64)

Public (no auth):

- `GET /api/public/export-signing-key` - public key for verifying signed export ZIPs

### PII key ring configuration

Preferred config:

```yaml
app:
  pii:
    primary-key-id: "k2026-01"
    keys:
      - id: "k2026-01"
        key-base64: "<base64-32-bytes>"
        enabled: true
      - id: "k2025-06"
        key-base64: "<base64-32-bytes>"
        enabled: true
```

If `app.pii.keys` is omitted, legacy `app.pii.master-key-base64` is used under the implicit id `legacy`.

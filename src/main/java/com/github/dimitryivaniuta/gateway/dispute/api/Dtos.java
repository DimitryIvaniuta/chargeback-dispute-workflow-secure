package com.github.dimitryivaniuta.gateway.dispute.api;

import com.github.dimitryivaniuta.gateway.dispute.domain.CaseState;
import com.github.dimitryivaniuta.gateway.dispute.domain.CaseTeam;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API DTOs for dispute case management.
 */
public final class Dtos {

  private Dtos() {}

  /**
   * Optional PII for a dispute case (encrypted at rest).
   *
   * <p>Access to decrypted PII is restricted by {@code ROLE_DISPUTE_PII_VIEW} or {@code ROLE_DISPUTE_ADMIN}.</p>
   */
  public record CustomerPii(
      @Email @Size(max = 320) String email,
      @Size(max = 200) String fullName,
      @Size(max = 40) String phone
  ) {}

  /**
   * Request to open a new dispute case.
   *
   * <p>{@code customerRef} can be any external identifier (order id, customer id, etc.). It is stored as a hash
   * ({@code customerRefHash}) to reduce PII footprint.</p>
   */
  public record CreateCaseRequest(
      @NotBlank @Size(max = 120) String externalRef,
      @NotBlank @Size(max = 320) String customerRef,
      @Min(1) long amountCents,
      @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
      @NotNull CaseTeam assignedTeam,
      @Size(max = 2000) String description,
      CustomerPii pii
  ) {}

  /**
   * Request to update encrypted PII for an existing case.
   */
  public record UpdatePiiRequest(
      @NotNull CustomerPii pii,
      @Size(max = 2000) String note
  ) {}

  /**
   * Request to transition case state.
   */
  public record UpdateStateRequest(
      @NotNull CaseState targetState,
      @Size(max = 2000) String note
  ) {}

  /**
   * Request to assign a case to a new team.
   */
  public record AssignTeamRequest(
      @NotNull CaseTeam assignedTeam,
      @Size(max = 2000) String note
  ) {}

  /**
   * Request to register an attachment's metadata (binary is uploaded to secure storage separately).
   */
  public record RegisterAttachmentRequest(
      @NotBlank @Size(max = 255) String filename,
      @NotBlank @Size(max = 120) String contentType,
      @Min(1) long sizeBytes,
      @NotBlank @Pattern(regexp = "^[a-fA-F0-9]{64}$") String sha256
  ) {}

  /**
   * Request to generate a presigned upload URL for secure attachment storage.
   */
  public record PresignUploadRequest(
      @NotBlank @Size(max = 255) String filename,
      @NotBlank @Size(max = 120) String contentType,
      @Min(1) long sizeBytes,
      @NotBlank @Pattern(regexp = "^[a-fA-F0-9]{64}$") String sha256
  ) {}

  /**
   * Presign upload response (client uploads binary directly to storage).
   *
   * @param headers optional extra headers required by the storage provider (usually empty for S3 presigned URLs)
   */
  public record PresignUploadResponse(
      UUID attachmentId,
      String storageKey,
      String uploadUrl,
      Instant expiresAt,
      Map<String, String> headers
  ) {}

  /**
   * Presign download response (client downloads binary directly from storage).
   */
  public record PresignDownloadResponse(
      UUID attachmentId,
      String storageKey,
      String downloadUrl,
      Instant expiresAt
  ) {}

  public record AttachmentMetadata(
      UUID id,
      String storageKey,
      String filename,
      String contentType,
      long sizeBytes,
      String sha256,
      Instant uploadedAt,
      String uploadedBy
  ) {}

  /**
   * Case response.
   *
   * <p>PII is included only when the caller is authorized; otherwise {@code pii} is {@code null}.</p>
   */
  public record CaseResponse(
      UUID id,
      String externalRef,
      String customerRefHash,
      long amountCents,
      String currency,
      CaseState state,
      CaseTeam assignedTeam,
      Instant openedAt,
      Instant dueAt,
      Instant closedAt,
      boolean legalHold,
      Instant lastUpdatedAt,
      String lastUpdatedBy,
      String description,
      CustomerPii pii,
      List<AttachmentMetadata> attachments
  ) {}

  /**
   * Generic audit entry response.
   */
  public record AuditEntry(
      UUID id,
      UUID caseId,
      Instant occurredAt,
      String actor,
      String action,
      String correlationId,
      String details
  ) {}

  /**
   * Demo-only request to issue a local JWT for testing.
   */
  public record IssueTokenRequest(
      @NotBlank @Size(max = 120) String subject,
      @NotNull CaseTeam team,
      @NotNull @Size(min = 1) List<@NotBlank String> roles
  ) {}

  /**
   * Demo-only token response.
   */
  public record IssueTokenResponse(String token) {}


  /**
   * Admin-only request to enable/disable legal hold (prevents PII purge).
   */
  public record LegalHoldRequest(
      boolean legalHold,
      @Size(max = 2000) String note
  ) {}


  /**
   * Public export signing key metadata.
   *
   *  algorithm signing algorithm (e.g., Ed25519)
   *  keyId stable identifier (sha-256 of public key)
   *  publicKeyX509Base64 X.509 SPKI Base64
   */
  public record ExportSigningPublicKeyResponse(String algorithm, String keyId, String publicKeyX509Base64) {}


  /**
   * Admin-only request to schedule a background re-encryption job of PII envelopes from {@code oldKid} to {@code newKid}.
   *
   * <p>Use after a rotation runbook promoted a new primary key, to gradually re-wrap existing envelopes.
   * The job runs in throttled batches to avoid DB pressure.</p>
   */
  public record ReencryptPiiRequest(
      @NotBlank @Size(max = 64) String oldKid,
      @NotBlank @Size(max = 64) String newKid,
      @Min(1) @Max(500) int batchSize,
      @Min(0) @Max(60000) int delayMs,
      boolean dryRun
  ) {}

  /** Response for a PII re-encryption job. */
  public record ReencryptJobResponse(
      UUID jobId,
      String oldKid,
      String newKid,
      String status,
      Instant createdAt,
      Instant startedAt,
      Instant finishedAt,
      long total,
      long processed,
      long failures,
      boolean cancelRequested,
      String lastError
  ) {}

}

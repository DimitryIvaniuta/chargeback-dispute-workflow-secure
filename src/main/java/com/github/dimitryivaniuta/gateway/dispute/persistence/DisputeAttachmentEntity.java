package com.github.dimitryivaniuta.gateway.dispute.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * Attachment metadata linked to a dispute case.
 *
 * <p>IMPORTANT: Only metadata is stored in the database. The binary payload MUST be stored
 * in secure external storage (e.g., S3 with encryption + access policies).</p>
 */
@Entity
@Table(name = "dispute_attachments")
@Getter
@Setter
public class DisputeAttachmentEntity {

  @Id
  @Column(nullable = false, updatable = false)
  private UUID id;

  @Column(name = "case_id", nullable = false)
  private UUID caseId;

  @Column(name = "storage_key", nullable = false, length = 512)
  private String storageKey;

  @Column(nullable = false, length = 255)
  private String filename;

  @Column(name = "content_type", nullable = false, length = 120)
  private String contentType;

  @Column(name = "size_bytes", nullable = false)
  private long sizeBytes;

  @Column(nullable = false, length = 64)
  private String sha256;

  @Column(name = "uploaded_at", nullable = false)
  private Instant uploadedAt;

  @Column(name = "uploaded_by", nullable = false, length = 120)
  private String uploadedBy;
}

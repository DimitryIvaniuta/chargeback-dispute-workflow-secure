package com.github.dimitryivaniuta.gateway.dispute.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * Tracks operational PII envelope re-encryption jobs (old kid -> new kid).
 *
 * <p>Jobs are executed by a background worker in throttled batches to avoid DB pressure.
 * The job record provides progress and supports cancellation.</p>
 */
@Entity
@Table(name = "dispute_pii_reencrypt_jobs")
@Getter
@Setter
public class DisputePiiReencryptJobEntity {

  @Id
  @Column(nullable = false, updatable = false)
  private UUID id;

  @Column(name = "old_kid", nullable = false, length = 64)
  private String oldKid;

  @Column(name = "new_kid", nullable = false, length = 64)
  private String newKid;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 24)
  private PiiReencryptJobStatus status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "finished_at")
  private Instant finishedAt;

  @Column(name = "requested_by", nullable = false, length = 120)
  private String requestedBy;

  @Column(name = "batch_size", nullable = false)
  private int batchSize;

  @Column(name = "delay_ms", nullable = false)
  private int delayMs;

  @Column(nullable = false)
  private long total;

  @Column(nullable = false)
  private long processed;

  @Column(nullable = false)
  private long failures;

  @Lob
  @Column(name = "last_error")
  private String lastError;

  @Column(name = "cancel_requested", nullable = false)
  private boolean cancelRequested;
}

package com.github.dimitryivaniuta.gateway.dispute.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * Immutable audit record for a dispute case change.
 *
 * <p>This table is append-only. Do NOT update/delete audit rows.</p>
 */
@Entity
@Table(name = "dispute_audit_log")
@Getter
@Setter
public class DisputeAuditLogEntity {

  @Id
  @Column(nullable = false, updatable = false)
  private UUID id;

  @Column(name = "case_id", nullable = false)
  private UUID caseId;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  @Column(nullable = false, length = 120)
  private String actor;

  @Column(nullable = false, length = 80)
  private String action;

  @Column(name = "correlation_id", length = 64)
  private String correlationId;

  @Lob
  @Column(name = "before_json")
  private String beforeJson;

  @Lob
  @Column(name = "after_json")
  private String afterJson;

  @Column(length = 2000)
  private String details;
}

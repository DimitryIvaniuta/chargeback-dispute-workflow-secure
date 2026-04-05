package com.github.dimitryivaniuta.gateway.dispute.persistence;

import com.github.dimitryivaniuta.gateway.dispute.domain.CaseState;
import com.github.dimitryivaniuta.gateway.dispute.domain.CaseTeam;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * Aggregate root representing a dispute/chargeback case.
 *
 * <p>Security model: case is assigned to a {@link CaseTeam}. Only users in that team
 * (or administrators/auditors) can view; only editors/admin can update.</p>
 *
 * <p>GDPR note: this entity stores a hash of the external customer reference ({@code customerRefHash})
 * and (optionally) an encrypted PII envelope ({@code piiEnvelope}). Decrypted PII is never logged and
 * is only returned to authorized callers.</p>
 */
@Entity
@Table(name = "dispute_cases")
@Getter
@Setter
public class DisputeCaseEntity {

  @Id
  @Column(nullable = false, updatable = false)
  private UUID id;

  /** Optimistic locking for concurrent updates. */
  @Version
  @Column(nullable = false)
  private long version;

  @Column(name = "external_ref", nullable = false, length = 120)
  private String externalRef;

  @Column(name = "customer_ref_hash", nullable = false, length = 64)
  private String customerRefHash;

  /**
   * Application-layer envelope encryption payload (Base64 JSON).
   * Nullable when no PII is stored or after PII retention purge.
   */
  @Lob
  @Column(name = "pii_envelope")
  private String piiEnvelope;

  /**
   * Key id of the master key used to encrypt {@link #piiEnvelope}.
   * This is a convenience column to allow efficient querying and operational rotation workflows.
   * Nullable when no PII is stored.
   */
  @Column(name = "pii_kid", length = 64)
  private String piiKid;


  @Column(name = "amount_cents", nullable = false)
  private long amountCents;

  @Column(nullable = false, length = 3)
  private String currency;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 40)
  private CaseState state;

  @Enumerated(EnumType.STRING)
  @Column(name = "assigned_team", nullable = false, length = 40)
  private CaseTeam assignedTeam;

  @Column(name = "opened_at", nullable = false)
  private Instant openedAt;

  @Column(name = "due_at")
  private Instant dueAt;

  @Column(name = "closed_at")
  private Instant closedAt;

  @Column(name = "legal_hold", nullable = false)
  private boolean legalHold;

  /**
   * Set to true once a deadline breach is detected and recorded (idempotent).
   */
  @Column(name = "deadline_breached", nullable = false)
  private boolean deadlineBreached;

  @Column(name = "last_updated_at", nullable = false)
  private Instant lastUpdatedAt;

  @Column(name = "last_updated_by", nullable = false, length = 120)
  private String lastUpdatedBy;

  @Column(length = 2000)
  private String description;
}

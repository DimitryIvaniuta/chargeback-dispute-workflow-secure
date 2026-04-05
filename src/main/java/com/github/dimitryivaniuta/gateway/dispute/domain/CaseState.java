package com.github.dimitryivaniuta.gateway.dispute.domain;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;

/**
 * Dispute case lifecycle state.
 *
 * <p>State values are stored as {@code VARCHAR} in the database (no DB-level enum constraints)
 * to allow safe evolution via application deployments.</p>
 */
public enum CaseState {
  OPEN,
  EVIDENCE_REQUESTED,
  UNDER_REVIEW,
  ACCEPTED,
  REJECTED,
  CLOSED;

  /**
   * Allowed next states (application-level state machine).
   *
   * @return allowed next states
   */
  public Set<CaseState> allowedNext() {
    return switch (this) {
      case OPEN -> EnumSet.of(EVIDENCE_REQUESTED, UNDER_REVIEW, CLOSED);
      case EVIDENCE_REQUESTED -> EnumSet.of(UNDER_REVIEW, CLOSED);
      case UNDER_REVIEW -> EnumSet.of(ACCEPTED, REJECTED, CLOSED);
      case ACCEPTED, REJECTED -> EnumSet.of(CLOSED);
      case CLOSED -> EnumSet.noneOf(CaseState.class);
    };
  }

  /**
   * Whether the state is terminal.
   *
   * @return true if terminal
   */
  public boolean isTerminal() {
    return this == CLOSED;
  }
}

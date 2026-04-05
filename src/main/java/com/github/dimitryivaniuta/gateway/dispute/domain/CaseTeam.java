package com.github.dimitryivaniuta.gateway.dispute.domain;

/**
 * Team assignment for a dispute case.
 *
 * <p>Stored as {@code VARCHAR} in DB.</p>
 */
public enum CaseTeam {
  CHARGEBACK,
  FRAUD,
  SUPPORT
}

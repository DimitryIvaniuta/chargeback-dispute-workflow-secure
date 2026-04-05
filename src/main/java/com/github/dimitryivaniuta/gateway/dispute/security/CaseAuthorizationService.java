package com.github.dimitryivaniuta.gateway.dispute.security;

import com.github.dimitryivaniuta.gateway.dispute.persistence.DisputeCaseRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

/**
 * Central authorization checks for case access.
 *
 * <p>Rules:</p>
 * <ul>
 *   <li>{@code ROLE_DISPUTE_ADMIN} can access any case (view/edit/PII)</li>
 *   <li>{@code ROLE_DISPUTE_AUDITOR} can view any case and audit logs (read-only)</li>
 *   <li>Otherwise, user must have {@code TEAM_<team>} matching case.assignedTeam</li>
 *   <li>Edits require {@code ROLE_DISPUTE_EDIT} (or admin)</li>
 *   <li>Decrypted PII access additionally requires {@code ROLE_DISPUTE_PII_VIEW} (or admin)</li>
 * </ul>
 */
@Component("caseAuth")
@RequiredArgsConstructor
public class CaseAuthorizationService {

  private final DisputeCaseRepository caseRepository;

  public boolean canView(UUID caseId, Authentication authentication) {
    return canAccess(caseId, authentication);
  }

  public boolean canEdit(UUID caseId, Authentication authentication) {
    if (!has(authentication, "ROLE_DISPUTE_EDIT") && !has(authentication, "ROLE_DISPUTE_ADMIN")) {
      return false;
    }
    return canAccess(caseId, authentication);
  }

  public boolean canViewPii(UUID caseId, Authentication authentication) {
    if (!has(authentication, "ROLE_DISPUTE_PII_VIEW") && !has(authentication, "ROLE_DISPUTE_ADMIN")) {
      return false;
    }
    return canAccess(caseId, authentication);
  }

  public boolean canAudit(Authentication authentication) {
    return has(authentication, "ROLE_DISPUTE_AUDITOR") || has(authentication, "ROLE_DISPUTE_ADMIN");
  }

  private boolean canAccess(UUID caseId, Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated()) {
      return false;
    }
    if (has(authentication, "ROLE_DISPUTE_ADMIN") || has(authentication, "ROLE_DISPUTE_AUDITOR")) {
      return true;
    }
    return caseRepository.findById(caseId)
        .map(c -> has(authentication, "TEAM_" + c.getAssignedTeam().name()))
        .orElse(false);
  }

  private boolean has(Authentication authentication, String authority) {
    if (authentication == null) return false;
    for (GrantedAuthority ga : authentication.getAuthorities()) {
      if (authority.equals(ga.getAuthority())) return true;
    }
    return false;
  }
}

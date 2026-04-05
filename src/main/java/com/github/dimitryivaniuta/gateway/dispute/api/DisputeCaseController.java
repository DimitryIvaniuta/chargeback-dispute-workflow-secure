package com.github.dimitryivaniuta.gateway.dispute.api;

import com.github.dimitryivaniuta.gateway.dispute.domain.CaseState;
import com.github.dimitryivaniuta.gateway.dispute.domain.CaseTeam;
import com.github.dimitryivaniuta.gateway.dispute.service.DisputeCaseService;
import com.github.dimitryivaniuta.gateway.dispute.service.DisputeException;
import com.github.dimitryivaniuta.gateway.dispute.support.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * REST API for dispute case management.
 */
@RestController
@RequestMapping("/api/cases")
@RequiredArgsConstructor
public class DisputeCaseController {

  private final DisputeCaseService caseService;

  @PostMapping
  @PreAuthorize("hasAnyAuthority('ROLE_DISPUTE_EDIT','ROLE_DISPUTE_ADMIN')")
  public Dtos.CaseResponse open(@Valid @RequestBody Dtos.CreateCaseRequest req,
                                @AuthenticationPrincipal Jwt jwt,
                                Authentication authentication,
                                HttpServletRequest request) {
    String actor = jwt.getSubject();
    String correlationId = (String) request.getAttribute(CorrelationIdFilter.MDC_KEY);

    // Non-admin can only open cases for their own team.
    if (!has(authentication, "ROLE_DISPUTE_ADMIN")) {
      String userTeam = jwt.getClaimAsString("team");
      if (userTeam == null || !userTeam.equals(req.assignedTeam().name())) {
        throw new DisputeException.Forbidden("Cannot open case for another team");
      }
    }
    return caseService.openCase(req, actor, correlationId);
  }

  @GetMapping("/{id}")
  @PreAuthorize("@caseAuth.canView(#id, authentication) and (hasAnyAuthority('ROLE_DISPUTE_VIEW','ROLE_DISPUTE_EDIT','ROLE_DISPUTE_ADMIN','ROLE_DISPUTE_AUDITOR'))")
  public Dtos.CaseResponse get(@PathVariable("id") UUID id,
                              Authentication authentication) {
    boolean includePii = has(authentication, "ROLE_DISPUTE_ADMIN") || has(authentication, "ROLE_DISPUTE_PII_VIEW");
    return caseService.getCase(id, includePii);
  }

  @GetMapping
  @PreAuthorize("hasAnyAuthority('ROLE_DISPUTE_VIEW','ROLE_DISPUTE_EDIT','ROLE_DISPUTE_ADMIN','ROLE_DISPUTE_AUDITOR')")
  public List<Dtos.CaseResponse> search(
      @RequestParam(value = "team", required = false) CaseTeam team,
      @RequestParam(value = "state", required = false) CaseState state,
      @RequestParam(value = "dueBefore", required = false)
      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant dueBefore,
      @AuthenticationPrincipal Jwt jwt,
      Authentication authentication
  ) {
    // Auditors/Admin can query any team. Non-admin non-auditors are restricted to their own team.
    if (!has(authentication, "ROLE_DISPUTE_ADMIN") && !has(authentication, "ROLE_DISPUTE_AUDITOR")) {
      String userTeam = jwt.getClaimAsString("team");
      CaseTeam enforced = userTeam == null ? null : CaseTeam.valueOf(userTeam);
      team = enforced;
    }
    return caseService.search(team, state, dueBefore);
  }

  @PutMapping("/{id}/state")
  @PreAuthorize("@caseAuth.canEdit(#id, authentication)")
  public Dtos.CaseResponse changeState(@PathVariable("id") UUID id,
                                      @Valid @RequestBody Dtos.UpdateStateRequest req,
                                      @AuthenticationPrincipal Jwt jwt,
                                      HttpServletRequest request) {
    String actor = jwt.getSubject();
    String correlationId = (String) request.getAttribute(CorrelationIdFilter.MDC_KEY);
    return caseService.transitionState(id, req.targetState(), req.note(), actor, correlationId);
  }

  @PutMapping("/{id}/team")
  @PreAuthorize("hasAuthority('ROLE_DISPUTE_ADMIN')")
  public Dtos.CaseResponse assignTeam(@PathVariable("id") UUID id,
                                     @Valid @RequestBody Dtos.AssignTeamRequest req,
                                     @AuthenticationPrincipal Jwt jwt,
                                     HttpServletRequest request) {
    String actor = jwt.getSubject();
    String correlationId = (String) request.getAttribute(CorrelationIdFilter.MDC_KEY);
    return caseService.assignTeam(id, req.assignedTeam(), req.note(), actor, correlationId);
  }

    @PutMapping("/{id}/legal-hold")
  @PreAuthorize("hasAuthority('ROLE_DISPUTE_ADMIN')")
  public Dtos.CaseResponse legalHold(@PathVariable("id") UUID id,
                                    @Valid @RequestBody Dtos.LegalHoldRequest req,
                                    @AuthenticationPrincipal Jwt jwt,
                                    HttpServletRequest request) {
    String actor = jwt.getSubject();
    String correlationId = (String) request.getAttribute(CorrelationIdFilter.MDC_KEY);
    return caseService.setLegalHold(id, req.legalHold(), req.note(), actor, correlationId);
  }

@PutMapping("/{id}/pii")
  @PreAuthorize("@caseAuth.canEdit(#id, authentication) and @caseAuth.canViewPii(#id, authentication)")
  public Dtos.CaseResponse updatePii(@PathVariable("id") UUID id,
                                    @Valid @RequestBody Dtos.UpdatePiiRequest req,
                                    @AuthenticationPrincipal Jwt jwt,
                                    HttpServletRequest request) {
    String actor = jwt.getSubject();
    String correlationId = (String) request.getAttribute(CorrelationIdFilter.MDC_KEY);
    return caseService.updatePii(id, req.pii(), req.note(), actor, correlationId);
  }

  @PostMapping("/{id}/attachments/register")
  @PreAuthorize("@caseAuth.canEdit(#id, authentication)")
  public Dtos.AttachmentMetadata registerAttachment(@PathVariable("id") UUID id,
                                                    @Valid @RequestBody Dtos.RegisterAttachmentRequest req,
                                                    @AuthenticationPrincipal Jwt jwt,
                                                    HttpServletRequest request) {
    String actor = jwt.getSubject();
    String correlationId = (String) request.getAttribute(CorrelationIdFilter.MDC_KEY);
    return caseService.registerAttachment(id, req, actor, correlationId);
  }

  @PostMapping("/{id}/attachments/presign-upload")
  @PreAuthorize("@caseAuth.canEdit(#id, authentication)")
  public Dtos.PresignUploadResponse presignUpload(@PathVariable("id") UUID id,
                                                  @Valid @RequestBody Dtos.PresignUploadRequest req,
                                                  @AuthenticationPrincipal Jwt jwt,
                                                  HttpServletRequest request) {
    String actor = jwt.getSubject();
    String correlationId = (String) request.getAttribute(CorrelationIdFilter.MDC_KEY);
    return caseService.presignUpload(id, req, actor, correlationId);
  }

  @GetMapping("/{id}/attachments/{attachmentId}/presign-download")
  @PreAuthorize("@caseAuth.canView(#id, authentication)")
  public Dtos.PresignDownloadResponse presignDownload(@PathVariable("id") UUID id,
                                                      @PathVariable("attachmentId") UUID attachmentId) {
    return caseService.presignDownload(id, attachmentId);
  }

  private boolean has(Authentication authentication, String authority) {
    if (authentication == null) return false;
    for (GrantedAuthority ga : authentication.getAuthorities()) {
      if (authority.equals(ga.getAuthority())) return true;
    }
    return false;
  }
}

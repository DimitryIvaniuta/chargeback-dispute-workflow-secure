package com.github.dimitryivaniuta.gateway.dispute.api;

import com.github.dimitryivaniuta.gateway.dispute.domain.CaseState;
import com.github.dimitryivaniuta.gateway.dispute.domain.CaseTeam;
import com.github.dimitryivaniuta.gateway.dispute.persistence.DisputeAuditLogRepository;
import com.github.dimitryivaniuta.gateway.dispute.persistence.DisputeCaseRepository;
import com.github.dimitryivaniuta.gateway.dispute.pii.PiiCryptoService;
import com.github.dimitryivaniuta.gateway.dispute.support.ExportPackageService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;

/**
 * Auditor tooling: export endpoints.
 *
 * <p>Exports are generated on-demand (not persisted) to reduce GDPR footprint.</p>
 */
@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditorController {

  private final DisputeCaseRepository caseRepository;
  private final DisputeAuditLogRepository auditRepository;
  private final PiiCryptoService piiCryptoService;
  private final ExportPackageService exportPackageService;

  @GetMapping(value = "/cases.csv", produces = "text/csv")
  @PreAuthorize("@caseAuth.canAudit(authentication)")
  public void exportCasesCsv(
      @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
      @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
      @RequestParam(value = "team", required = false) CaseTeam team,
      @RequestParam(value = "state", required = false) CaseState state,
      @RequestParam(value = "includePii", defaultValue = "false") boolean includePii,
      Authentication authentication,
      HttpServletResponse response
  ) throws Exception {

    boolean allowPii = includePii && (has(authentication, "ROLE_DISPUTE_ADMIN") || has(authentication, "ROLE_DISPUTE_PII_VIEW"));

    response.setHeader("Content-Disposition", "attachment; filename=cases-export.csv");
    try (PrintWriter w = response.getWriter()) {
      writeCasesCsv(w, from, to, team, state, allowPii);
    }
  }

  @GetMapping(value = "/cases.zip", produces = "application/zip")
  @PreAuthorize("@caseAuth.canAudit(authentication)")
  public void exportCasesZip(
      @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
      @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
      @RequestParam(value = "team", required = false) CaseTeam team,
      @RequestParam(value = "state", required = false) CaseState state,
      @RequestParam(value = "includePii", defaultValue = "false") boolean includePii,
      Authentication authentication,
      HttpServletResponse response
  ) throws Exception {

    boolean allowPii = includePii && (has(authentication, "ROLE_DISPUTE_ADMIN") || has(authentication, "ROLE_DISPUTE_PII_VIEW"));

    String csv = buildCasesCsv(from, to, team, state, allowPii);
    byte[] zip = exportPackageService.createSignedZip("cases-export", csv.getBytes(StandardCharsets.UTF_8));

    response.setHeader("Content-Disposition", "attachment; filename=cases-export.zip");
    response.getOutputStream().write(zip);
  }

  @GetMapping(value = "/audit-log.csv", produces = "text/csv")
  @PreAuthorize("@caseAuth.canAudit(authentication)")
  public void exportAuditCsv(
      @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
      @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
      HttpServletResponse response
  ) throws Exception {
    response.setHeader("Content-Disposition", "attachment; filename=audit-export.csv");
    try (PrintWriter w = response.getWriter()) {
      writeAuditCsv(w, from, to);
    }
  }

  @GetMapping(value = "/audit-log.zip", produces = "application/zip")
  @PreAuthorize("@caseAuth.canAudit(authentication)")
  public void exportAuditZip(
      @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
      @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
      HttpServletResponse response
  ) throws Exception {
    String csv = buildAuditCsv(from, to);
    byte[] zip = exportPackageService.createSignedZip("audit-export", csv.getBytes(StandardCharsets.UTF_8));
    response.setHeader("Content-Disposition", "attachment; filename=audit-export.zip");
    response.getOutputStream().write(zip);
  }

  @GetMapping(value = "/cases/{caseId}/audit.csv", produces = "text/csv")
  @PreAuthorize("@caseAuth.canView(#caseId, authentication) and @caseAuth.canAudit(authentication)")
  public void exportCaseAuditCsv(@PathVariable UUID caseId, HttpServletResponse response) throws Exception {
    response.setHeader("Content-Disposition", "attachment; filename=case-audit-" + caseId + ".csv");
    try (PrintWriter w = response.getWriter()) {
      writeCaseAuditCsv(w, caseId);
    }
  }

  @GetMapping(value = "/cases/{caseId}/audit.zip", produces = "application/zip")
  @PreAuthorize("@caseAuth.canView(#caseId, authentication) and @caseAuth.canAudit(authentication)")
  public void exportCaseAuditZip(@PathVariable UUID caseId, HttpServletResponse response) throws Exception {
    String csv = buildCaseAuditCsv(caseId);
    byte[] zip = exportPackageService.createSignedZip("case-audit-" + caseId, csv.getBytes(StandardCharsets.UTF_8));
    response.setHeader("Content-Disposition", "attachment; filename=case-audit-" + caseId + ".zip");
    response.getOutputStream().write(zip);
  }

  private void writeCasesCsv(PrintWriter w, Instant from, Instant to, CaseTeam team, CaseState state, boolean allowPii) throws Exception {
    w.println("id,externalRef,customerRefHash,amountCents,currency,state,assignedTeam,openedAt,dueAt,closedAt,legalHold,lastUpdatedAt,lastUpdatedBy,piiEmail,piiFullName,piiPhone");
    for (var c : caseRepository.exportCases(from, to, team, state)) {
      Dtos.CustomerPii pii = allowPii ? piiCryptoService.decrypt(c.getId(), c.getPiiEnvelope()) : null;
      w.printf("%s,%s,%s,%d,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s%n",
          c.getId(),
          csv(c.getExternalRef()),
          c.getCustomerRefHash(),
          c.getAmountCents(),
          c.getCurrency(),
          c.getState().name(),
          c.getAssignedTeam().name(),
          iso(c.getOpenedAt()),
          iso(c.getDueAt()),
          iso(c.getClosedAt()),
          c.isLegalHold(),
          iso(c.getLastUpdatedAt()),
          csv(c.getLastUpdatedBy()),
          csv(pii == null ? null : pii.email()),
          csv(pii == null ? null : pii.fullName()),
          csv(pii == null ? null : pii.phone())
      );
    }
  }

  private String buildCasesCsv(Instant from, Instant to, CaseTeam team, CaseState state, boolean allowPii) throws Exception {
    java.io.StringWriter sw = new java.io.StringWriter();
    try (PrintWriter w = new PrintWriter(sw)) {
      writeCasesCsv(w, from, to, team, state, allowPii);
    }
    return sw.toString();
  }

  private void writeAuditCsv(PrintWriter w, Instant from, Instant to) {
    w.println("id,caseId,occurredAt,actor,action,correlationId,details");
    for (var a : auditRepository.exportRange(from, to)) {
      w.printf("%s,%s,%s,%s,%s,%s,%s%n",
          a.getId(),
          a.getCaseId(),
          iso(a.getOccurredAt()),
          csv(a.getActor()),
          csv(a.getAction()),
          csv(a.getCorrelationId()),
          csv(a.getDetails())
      );
    }
  }

  private String buildAuditCsv(Instant from, Instant to) {
    java.io.StringWriter sw = new java.io.StringWriter();
    try (PrintWriter w = new PrintWriter(sw)) {
      writeAuditCsv(w, from, to);
    }
    return sw.toString();
  }

  private void writeCaseAuditCsv(PrintWriter w, UUID caseId) {
    w.println("id,caseId,occurredAt,actor,action,correlationId,details");
    for (var a : auditRepository.findByCaseIdOrderByOccurredAtDesc(caseId)) {
      w.printf("%s,%s,%s,%s,%s,%s,%s%n",
          a.getId(),
          a.getCaseId(),
          iso(a.getOccurredAt()),
          csv(a.getActor()),
          csv(a.getAction()),
          csv(a.getCorrelationId()),
          csv(a.getDetails())
      );
    }
  }

  private String buildCaseAuditCsv(UUID caseId) {
    java.io.StringWriter sw = new java.io.StringWriter();
    try (PrintWriter w = new PrintWriter(sw)) {
      writeCaseAuditCsv(w, caseId);
    }
    return sw.toString();
  }

  private String iso(Instant i) {
    if (i == null) return "";
    return DateTimeFormatter.ISO_INSTANT.format(i);
  }

  private String csv(String v) {
    if (v == null) return "";
    String s = v.replace("\"", "\"\"");
    return "\"" + s + "\"";
  }

  private boolean has(Authentication authentication, String authority) {
    if (authentication == null) return false;
    for (GrantedAuthority ga : authentication.getAuthorities()) {
      if (authority.equals(ga.getAuthority())) return true;
    }
    return false;
  }
}

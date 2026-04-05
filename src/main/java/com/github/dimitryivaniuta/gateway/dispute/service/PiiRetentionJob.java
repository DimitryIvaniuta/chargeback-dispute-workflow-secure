package com.github.dimitryivaniuta.gateway.dispute.service;

import com.github.dimitryivaniuta.gateway.dispute.persistence.DisputeCaseEntity;
import com.github.dimitryivaniuta.gateway.dispute.persistence.DisputeCaseRepository;
import com.github.dimitryivaniuta.gateway.dispute.pii.PiiProperties;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * PII retention enforcement job.
 *
 * <p>When a case is closed and not on legal hold, encrypted PII is purged after a configured retention period.
 * The purge is auditable (PII_PURGED) while keeping non-PII business data.</p>
 */
@Component
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
public class PiiRetentionJob {

  private final DisputeCaseRepository caseRepository;
  private final AuditService auditService;
  private final DisputeEventPublisher eventPublisher;
  private final PiiProperties piiProperties;

  @Scheduled(cron = "0 */15 * * * *") // every 15 minutes
  @Transactional
  public void purgeExpiredPii() {
    if (piiProperties.retentionAfterClose() == null) return;

    Instant cutoff = Instant.now().minus(piiProperties.retentionAfterClose());
    List<DisputeCaseEntity> candidates = caseRepository.findPiiPurgeCandidates(cutoff);

    for (DisputeCaseEntity c : candidates) {
      // idempotent: only purge if still present
      if (c.getPiiEnvelope() == null) continue;

      log.info("Purging PII for caseId={} closedAt={}", c.getId(), c.getClosedAt());
      c.setPiiEnvelope(null);
      c.setPiiKid(null);
      c.setLastUpdatedAt(Instant.now());
      c.setLastUpdatedBy("system");
      caseRepository.save(c);

      auditService.record(c.getId(), "system", "PII_PURGED", null, null,
          "RetentionAfterClose=" + piiProperties.retentionAfterClose(), "retention-job");

      eventPublisher.publish(c.getExternalRef(), new DisputeEvent(c.getId(), c.getExternalRef(), c.getState(), c.getAssignedTeam(), "PII_PURGED", Instant.now()));
    }
  }
}

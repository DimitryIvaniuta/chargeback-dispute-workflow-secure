package com.github.dimitryivaniuta.gateway.dispute.service;

import com.github.dimitryivaniuta.gateway.dispute.persistence.DisputeCaseEntity;
import com.github.dimitryivaniuta.gateway.dispute.persistence.DisputeCaseRepository;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Periodically scans for overdue cases and records a breach exactly once per case.
 */
@Component
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
public class DeadlineMonitor {

  private final DisputeCaseRepository caseRepository;
  private final DisputeCaseService caseService;

  @Scheduled(fixedDelayString = "PT60S")
  @Transactional
  public void scanOverdue() {
    Instant now = Instant.now();
    List<DisputeCaseEntity> overdue = caseRepository.search(null, null, now);

    for (DisputeCaseEntity c : overdue) {
      if (c.getDueAt() == null || c.getState().isTerminal()) continue;
      if (c.isDeadlineBreached()) continue;

      log.warn("Deadline breached caseId={} state={} dueAt={}", c.getId(), c.getState(), c.getDueAt());
      caseService.markDeadlineBreached(c.getId(), "system", "scheduler");
    }
  }
}

package com.github.dimitryivaniuta.gateway.dispute.service;

import com.github.dimitryivaniuta.gateway.dispute.persistence.DisputeCaseEntity;
import com.github.dimitryivaniuta.gateway.dispute.persistence.DisputeCaseRepository;
import com.github.dimitryivaniuta.gateway.dispute.persistence.DisputePiiReencryptJobEntity;
import com.github.dimitryivaniuta.gateway.dispute.persistence.DisputePiiReencryptJobRepository;
import com.github.dimitryivaniuta.gateway.dispute.persistence.PiiReencryptJobStatus;
import com.github.dimitryivaniuta.gateway.dispute.pii.PiiCryptoService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Background worker that processes PII envelope re-encryption jobs.
 *
 * <p>Jobs are executed in small batches with an optional delay between batches to reduce load.
 * This is the final step in a full rotation lifecycle: after promoting a new primary key, re-wrap
 * historical envelopes to the new {@code kid}.</p>
 */
@Component
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
public class PiiReencryptionJobRunner {

  private final DisputePiiReencryptJobRepository jobRepository;
  private final DisputeCaseRepository caseRepository;
  private final AuditService auditService;
  private final DisputeEventPublisher eventPublisher;
  private final PiiCryptoService piiCryptoService;

  /** Polls for pending jobs. Interval is short; work is throttled per job. */
  @Scheduled(fixedDelayString = "${app.pii.reencrypt.poll-interval-ms:5000}")
  public void poll() {
    DisputePiiReencryptJobEntity job = jobRepository.findTopByStatusOrderByCreatedAtAsc(PiiReencryptJobStatus.PENDING)
        .orElse(null);
    if (job == null) return;

    try {
      runJob(job.getId());
    } catch (Exception e) {
      log.error("PII re-encryption job failed id={}", job.getId(), e);
    }
  }

  @Transactional
  public void requestCancel(UUID jobId) {
    DisputePiiReencryptJobEntity job = jobRepository.findById(jobId)
        .orElseThrow(() -> new DisputeException.NotFound("Re-encryption job not found: " + jobId));
    job.setCancelRequested(true);
    jobRepository.save(job);
  }

  /**
   * Runs a job end-to-end. Called by scheduler; split into small transactional batches.
   */
  public void runJob(UUID jobId) {
    // Mark running
    DisputePiiReencryptJobEntity job = markRunning(jobId);
    if (job == null) return;

    long processed = job.getProcessed();
    long failures = job.getFailures();
    long totalSeen = job.getTotal();

    while (true) {
      // Refresh cancel flag without holding a long transaction
      if (isCancelRequested(jobId)) {
        markCancelled(jobId);
        return;
      }

      BatchResult r = processNextBatch(jobId);
      processed += r.processed;
      failures += r.failures;
      totalSeen += r.totalConsidered;

      updateProgress(jobId, totalSeen, processed, failures, r.lastError);

      if (r.done) {
        if (r.lastError != null && !r.lastError.isBlank()) {
          markFailed(jobId, r.lastError);
        } else {
          markCompleted(jobId);
        }
        return;
      }

      if (r.delayMs > 0) {
        try {
          Thread.sleep(r.delayMs);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          markFailed(jobId, "Interrupted");
          return;
        }
      }
    }
  }

  @Transactional
  protected DisputePiiReencryptJobEntity markRunning(UUID jobId) {
    DisputePiiReencryptJobEntity job = jobRepository.findById(jobId).orElse(null);
    if (job == null) return null;
    if (job.getStatus() != PiiReencryptJobStatus.PENDING) return null;

    job.setStatus(PiiReencryptJobStatus.RUNNING);
    job.setStartedAt(Instant.now());
    jobRepository.save(job);
    return job;
  }

  @Transactional(readOnly = true)
  protected boolean isCancelRequested(UUID jobId) {
    return jobRepository.findById(jobId).map(DisputePiiReencryptJobEntity::isCancelRequested).orElse(true);
  }

  @Transactional
  protected void updateProgress(UUID jobId, long total, long processed, long failures, String lastError) {
    DisputePiiReencryptJobEntity job = jobRepository.findById(jobId).orElse(null);
    if (job == null) return;
    job.setTotal(total);
    job.setProcessed(processed);
    job.setFailures(failures);
    if (lastError != null && !lastError.isBlank()) job.setLastError(lastError);
    jobRepository.save(job);
  }

  @Transactional
  protected void markCompleted(UUID jobId) {
    DisputePiiReencryptJobEntity job = jobRepository.findById(jobId).orElse(null);
    if (job == null) return;
    job.setStatus(PiiReencryptJobStatus.COMPLETED);
    job.setFinishedAt(Instant.now());
    jobRepository.save(job);
  }

  @Transactional
  protected void markCancelled(UUID jobId) {
    DisputePiiReencryptJobEntity job = jobRepository.findById(jobId).orElse(null);
    if (job == null) return;
    job.setStatus(PiiReencryptJobStatus.CANCELLED);
    job.setFinishedAt(Instant.now());
    jobRepository.save(job);
  }

  @Transactional
  protected void markFailed(UUID jobId, String err) {
    DisputePiiReencryptJobEntity job = jobRepository.findById(jobId).orElse(null);
    if (job == null) return;
    job.setStatus(PiiReencryptJobStatus.FAILED);
    job.setLastError(err);
    job.setFinishedAt(Instant.now());
    jobRepository.save(job);
  }

  /** Result of a single batch step. */
  protected record BatchResult(boolean done, int delayMs, long totalConsidered, long processed, long failures, String lastError) {}

  @Transactional
  protected BatchResult processNextBatch(UUID jobId) {
    DisputePiiReencryptJobEntity job = jobRepository.findById(jobId)
        .orElseThrow(() -> new DisputeException.NotFound("Re-encryption job not found: " + jobId));
    if (job.getStatus() != PiiReencryptJobStatus.RUNNING) {
      return new BatchResult(true, 0, 0, 0, 0, "Job not RUNNING");
    }

    int batchSize = job.getBatchSize();
    int delayMs = job.getDelayMs();
    String oldKid = job.getOldKid();
    String newKid = job.getNewKid();

    // Fetch a slice of candidates. We scan by id order; skip ones without PII.
    List<DisputeCaseEntity> candidates = caseRepository.findReencryptCandidates(oldKid, batchSize);

    if (candidates.isEmpty()) {
      return new BatchResult(true, delayMs, 0, 0, 0, null);
    }

    long considered = 0;
    long processed = 0;
    long failures = 0;
    String lastError = null;

    for (DisputeCaseEntity c : candidates) {
      if (job.isCancelRequested()) break;

      if (c.getPiiEnvelope() == null || c.getPiiEnvelope().isBlank()) {
        considered++;
        continue;
      }

      String kid = c.getPiiKid();
      if (kid == null || kid.isBlank()) {
        // best-effort extraction for older rows
        kid = piiCryptoService.extractKid(c.getPiiEnvelope());
        if (kid != null && !kid.isBlank()) {
          // Backfill pii_kid to avoid rescanning the same row forever.
          c.setPiiKid(kid);
          c.setLastUpdatedAt(Instant.now());
          c.setLastUpdatedBy("system-reencrypt");
          caseRepository.save(c);
        }
      }

      if (kid == null || !kid.equals(oldKid)) {
        considered++;
        continue;
      }

      considered++;

      try {
        // re-wrap envelope with new kid
        String newEnvelope = piiCryptoService.reencrypt(c.getId(), c.getPiiEnvelope(), newKid);

        // dry-run is handled by the creator (no job created), so a job always performs changes
        c.setPiiEnvelope(newEnvelope);
        c.setPiiKid(newKid);
        c.setLastUpdatedAt(Instant.now());
        c.setLastUpdatedBy("system-reencrypt");
        caseRepository.save(c);

        auditService.record(c.getId(), "system-reencrypt", "PII_REENCRYPTED", null, null,
            "oldKid=" + oldKid + ",newKid=" + newKid + ",jobId=" + jobId, "reencrypt-job");

        eventPublisher.publish(c.getExternalRef(), new DisputeEvent(c.getId(), c.getExternalRef(), c.getState(), c.getAssignedTeam(), "PII_REENCRYPTED", Instant.now()));

        processed++;
      } catch (Exception e) {
        failures++;
        lastError = e.getMessage();
        log.warn("Failed to re-encrypt PII for caseId={} jobId={} err={}", c.getId(), jobId, e.toString());
      }
    }

    // We don't know completion based solely on batch; completion is when no candidates remain matching oldKid.
    // We'll allow the loop to continue until repository returns empty.
    return new BatchResult(false, delayMs, considered, processed, failures, lastError);
  }
}

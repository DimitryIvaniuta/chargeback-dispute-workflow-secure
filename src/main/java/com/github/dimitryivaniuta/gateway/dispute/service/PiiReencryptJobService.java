package com.github.dimitryivaniuta.gateway.dispute.service;

import com.github.dimitryivaniuta.gateway.dispute.api.Dtos;
import com.github.dimitryivaniuta.gateway.dispute.persistence.DisputePiiReencryptJobEntity;
import com.github.dimitryivaniuta.gateway.dispute.persistence.DisputePiiReencryptJobRepository;
import com.github.dimitryivaniuta.gateway.dispute.persistence.PiiReencryptJobStatus;
import com.github.dimitryivaniuta.gateway.dispute.persistence.DisputeCaseRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for scheduling and observing PII envelope re-encryption jobs.
 */
@Service
@RequiredArgsConstructor
public class PiiReencryptJobService {

  private final DisputePiiReencryptJobRepository jobRepository;
  private final DisputeCaseRepository caseRepository;
  private final PiiReencryptionJobRunner runner;

  /**
   * Schedules a new background job to re-encrypt envelopes from {@code oldKid} to {@code newKid}.
   *
   * <p>If {@code dryRun=true}, no job is created; only a preview is returned.</p>
   */
  @Transactional
  public Dtos.ReencryptJobResponse schedule(Dtos.ReencryptPiiRequest req, String actor) {
    long approxTotal = caseRepository.countByPiiKid(req.oldKid());

    if (req.dryRun()) {
      return new Dtos.ReencryptJobResponse(
          null,
          req.oldKid(),
          req.newKid(),
          "DRY_RUN",
          Instant.now(),
          null,
          null,
          approxTotal,
          0,
          0,
          false,
          "Preview only. total is approximate (rows with pii_kid null are not included)."
      );
    }

    DisputePiiReencryptJobEntity j = new DisputePiiReencryptJobEntity();
    j.setId(UUID.randomUUID());
    j.setOldKid(req.oldKid());
    j.setNewKid(req.newKid());
    j.setStatus(PiiReencryptJobStatus.PENDING);
    j.setCreatedAt(Instant.now());
    j.setRequestedBy(actor);
    j.setBatchSize(req.batchSize());
    j.setDelayMs(req.delayMs());
    j.setTotal(approxTotal);
    j.setProcessed(0);
    j.setFailures(0);
    j.setCancelRequested(false);

    jobRepository.save(j);

    return toResponse(j);
  }

  @Transactional(readOnly = true)
  public Dtos.ReencryptJobResponse get(UUID jobId) {
    DisputePiiReencryptJobEntity j = jobRepository.findById(jobId)
        .orElseThrow(() -> new DisputeException.NotFound("Re-encryption job not found: " + jobId));
    return toResponse(j);
  }

  @Transactional
  public Dtos.ReencryptJobResponse cancel(UUID jobId) {
    runner.requestCancel(jobId);
    return get(jobId);
  }

  private static Dtos.ReencryptJobResponse toResponse(DisputePiiReencryptJobEntity j) {
    return new Dtos.ReencryptJobResponse(
        j.getId(),
        j.getOldKid(),
        j.getNewKid(),
        j.getStatus().name(),
        j.getCreatedAt(),
        j.getStartedAt(),
        j.getFinishedAt(),
        j.getTotal(),
        j.getProcessed(),
        j.getFailures(),
        j.isCancelRequested(),
        j.getLastError()
    );
  }
}

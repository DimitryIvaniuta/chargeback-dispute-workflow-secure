package com.github.dimitryivaniuta.gateway.dispute.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimitryivaniuta.gateway.dispute.persistence.DisputeAuditLogEntity;
import com.github.dimitryivaniuta.gateway.dispute.persistence.DisputeAuditLogRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Creates immutable audit records for case changes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

  private final DisputeAuditLogRepository auditRepository;
  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

  /**
   * Records an audit event.
   *
   * @param caseId case id
   * @param actor who performed the action
   * @param action logical action name (e.g. STATE_CHANGED)
   * @param before previous state snapshot (serialized to JSON), may be null
   * @param after new state snapshot (serialized to JSON), may be null
   * @param details additional human-readable details, may be null
   * @param correlationId correlation id, may be null
   */
  public void record(UUID caseId, String actor, String action, Object before, Object after, String details, String correlationId) {
    DisputeAuditLogEntity e = new DisputeAuditLogEntity();
    e.setId(UUID.randomUUID());
    e.setCaseId(caseId);
    e.setOccurredAt(Instant.now());
    e.setActor(actor);
    e.setAction(action);
    e.setCorrelationId(correlationId);

    try {
      e.setBeforeJson(before == null ? null : objectMapper.writeValueAsString(before));
      e.setAfterJson(after == null ? null : objectMapper.writeValueAsString(after));
    } catch (Exception ex) {
      log.warn("Failed to serialize audit JSON, storing null snapshots: {}", ex.getMessage());
      e.setBeforeJson(null);
      e.setAfterJson(null);
    }
    e.setDetails(details);
    auditRepository.save(e);
  }
}

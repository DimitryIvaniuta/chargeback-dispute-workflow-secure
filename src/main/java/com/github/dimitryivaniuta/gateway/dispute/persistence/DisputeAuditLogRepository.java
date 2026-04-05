package com.github.dimitryivaniuta.gateway.dispute.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for immutable audit records.
 */
public interface DisputeAuditLogRepository extends JpaRepository<DisputeAuditLogEntity, UUID> {

  List<DisputeAuditLogEntity> findByCaseIdOrderByOccurredAtDesc(UUID caseId);

  boolean existsByCaseIdAndAction(UUID caseId, String action);

  @Query("""select a from DisputeAuditLogEntity a
      where a.occurredAt >= :from and a.occurredAt < :to
      order by a.occurredAt asc""")
  List<DisputeAuditLogEntity> exportRange(@Param("from") Instant from, @Param("to") Instant to);
}

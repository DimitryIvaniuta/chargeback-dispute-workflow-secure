package com.github.dimitryivaniuta.gateway.dispute.persistence;

import com.github.dimitryivaniuta.gateway.dispute.domain.CaseState;
import com.github.dimitryivaniuta.gateway.dispute.domain.CaseTeam;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for dispute cases.
 */
public interface DisputeCaseRepository extends JpaRepository<DisputeCaseEntity, UUID> {

  Optional<DisputeCaseEntity> findByExternalRef(String externalRef);

  List<DisputeCaseEntity> findByAssignedTeam(CaseTeam team);

  long countByPiiKid(String piiKid);

  @Query("""select c from DisputeCaseEntity c
      where (:team is null or c.assignedTeam = :team)
        and (:state is null or c.state = :state)
        and (:dueBefore is null or c.dueAt < :dueBefore)
      order by c.lastUpdatedAt desc""")
  List<DisputeCaseEntity> search(@Param("team") CaseTeam team,
                                @Param("state") CaseState state,
                                @Param("dueBefore") Instant dueBefore);

  @Query("""select c from DisputeCaseEntity c
      where c.closedAt is not null
        and c.closedAt < :closedBefore
        and c.legalHold = false
        and c.piiEnvelope is not null""")
  List<DisputeCaseEntity> findPiiPurgeCandidates(@Param("closedBefore") Instant closedBefore);

  @Query("""select c from DisputeCaseEntity c
      where (:from is null or c.openedAt >= :from)
        and (:to is null or c.openedAt < :to)
        and (:team is null or c.assignedTeam = :team)
        and (:state is null or c.state = :state)
      order by c.openedAt asc""")
  List<DisputeCaseEntity> exportCases(@Param("from") Instant from,
                                     @Param("to") Instant to,
                                     @Param("team") CaseTeam team,
                                     @Param("state") CaseState state);


  /**
   * Returns a small batch of cases that may require PII re-encryption from the given old kid.
   *
   * <p>Includes rows with {@code pii_kid is null} to allow best-effort backfill by parsing the envelope.</p>
   */
  @Query(value = "select * from dispute_cases where pii_envelope is not null and (pii_kid = :oldKid or pii_kid is null) order by id asc limit :limit", nativeQuery = true)
  List<DisputeCaseEntity> findReencryptCandidates(@Param("oldKid") String oldKid, @Param("limit") int limit);

}

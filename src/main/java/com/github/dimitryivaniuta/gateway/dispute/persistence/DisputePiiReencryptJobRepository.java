package com.github.dimitryivaniuta.gateway.dispute.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Repository for {@link DisputePiiReencryptJobEntity}. */
public interface DisputePiiReencryptJobRepository extends JpaRepository<DisputePiiReencryptJobEntity, UUID> {

  @Query("select j from DisputePiiReencryptJobEntity j where j.status = com.github.dimitryivaniuta.gateway.dispute.persistence.PiiReencryptJobStatus.PENDING order by j.createdAt asc")
  List<DisputePiiReencryptJobEntity> findPending();

  Optional<DisputePiiReencryptJobEntity> findTopByStatusOrderByCreatedAtAsc(PiiReencryptJobStatus status);
}

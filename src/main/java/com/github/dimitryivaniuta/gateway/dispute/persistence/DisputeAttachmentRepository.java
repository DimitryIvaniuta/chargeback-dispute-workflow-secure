package com.github.dimitryivaniuta.gateway.dispute.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for attachment metadata.
 */
public interface DisputeAttachmentRepository extends JpaRepository<DisputeAttachmentEntity, UUID> {

  List<DisputeAttachmentEntity> findByCaseIdOrderByUploadedAtDesc(UUID caseId);

  Optional<DisputeAttachmentEntity> findByIdAndCaseId(UUID id, UUID caseId);
}

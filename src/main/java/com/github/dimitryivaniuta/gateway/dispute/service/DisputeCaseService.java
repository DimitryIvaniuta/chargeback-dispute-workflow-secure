package com.github.dimitryivaniuta.gateway.dispute.service;

import com.github.dimitryivaniuta.gateway.dispute.api.Dtos;
import com.github.dimitryivaniuta.gateway.dispute.config.AttachmentProperties;
import com.github.dimitryivaniuta.gateway.dispute.config.DeadlineProperties;
import com.github.dimitryivaniuta.gateway.dispute.domain.CaseState;
import com.github.dimitryivaniuta.gateway.dispute.domain.CaseTeam;
import com.github.dimitryivaniuta.gateway.dispute.persistence.*;
import com.github.dimitryivaniuta.gateway.dispute.pii.PiiCryptoService;
import com.github.dimitryivaniuta.gateway.dispute.support.Sha256;
import com.github.dimitryivaniuta.gateway.dispute.storage.SecureStorageService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Core business service for dispute workflow.
 */
@Service
@RequiredArgsConstructor
public class DisputeCaseService {

  private final DisputeCaseRepository caseRepository;
  private final DisputeAttachmentRepository attachmentRepository;
  private final DisputeAuditLogRepository auditRepository;
  private final AuditService auditService;
  private final DeadlineProperties deadlineProperties;
  private final AttachmentProperties attachmentProperties;
  private final DisputeEventPublisher eventPublisher;
  private final PiiCryptoService piiCryptoService;
  private final SecureStorageService secureStorageService;

  /**
   * Opens a new dispute case.
   *
   * @param req create request
   * @param actor subject id of the actor (from JWT)
   * @param correlationId correlation id
   * @return created case response
   */
  @Transactional
  public Dtos.CaseResponse openCase(Dtos.CreateCaseRequest req, String actor, String correlationId) {
    DisputeCaseEntity e = new DisputeCaseEntity();
    e.setId(UUID.randomUUID());
    e.setExternalRef(req.externalRef());
    e.setCustomerRefHash(Sha256.hex(req.customerRef()));
    e.setPiiEnvelope(piiCryptoService.encrypt(e.getId(), req.pii()));
    e.setPiiKid(req.pii() == null ? null : piiCryptoService.primaryKid());
    e.setAmountCents(req.amountCents());
    e.setCurrency(req.currency());
    e.setState(CaseState.OPEN);
    e.setAssignedTeam(req.assignedTeam());
    Instant now = Instant.now();
    e.setOpenedAt(now);
    e.setLastUpdatedAt(now);
    e.setLastUpdatedBy(actor);
    e.setDescription(req.description());
    e.setLegalHold(false);
    e.setDeadlineBreached(false);

    var deadline = deadlineProperties.deadlineFor(e.getState().name());
    e.setDueAt(deadline == null ? null : now.plus(deadline));

    try {
      caseRepository.saveAndFlush(e);
    } catch (DataIntegrityViolationException ex) {
      // Unique externalRef violation
      throw new DisputeException.Conflict("Case with externalRef already exists: " + req.externalRef());
    }

    auditService.record(e.getId(), actor, "CASE_OPENED", null, auditSafe(e), "Opened case", correlationId);
    eventPublisher.publish(e.getExternalRef(), new DisputeEvent(e.getId(), e.getExternalRef(), e.getState(), e.getAssignedTeam(), "CASE_OPENED", Instant.now()));

    return toResponse(e, List.of(), false);
  }

  /**
   * Loads a case with attachment metadata (cached by id).
   *
   * @param includePii whether to decrypt and include PII in response
   */
  @Cacheable(cacheNames = "cases", key = "#caseId.toString().concat(':').concat(#includePii ? 'pii' : 'no')")
  @Transactional(readOnly = true)
  public Dtos.CaseResponse getCase(UUID caseId, boolean includePii) {
    DisputeCaseEntity e = caseRepository.findById(caseId)
        .orElseThrow(() -> new DisputeException.NotFound("Case not found: " + caseId));
    List<DisputeAttachmentEntity> atts = attachmentRepository.findByCaseIdOrderByUploadedAtDesc(caseId);
    return toResponse(e, atts, includePii);
  }

  /**
   * Searches cases for a team/state and optionally overdue cutoff.
   *
   * <p>PII is never included in search results.</p>
   */
  @Transactional(readOnly = true)
  public List<Dtos.CaseResponse> search(CaseTeam team, CaseState state, Instant dueBefore) {
    return caseRepository.search(team, state, dueBefore).stream()
        .map(e -> toResponse(e, attachmentRepository.findByCaseIdOrderByUploadedAtDesc(e.getId()), false))
        .toList();
  }

  /**
   * Transition case state with strict validation.
   */
  @CacheEvict(cacheNames = "cases", allEntries = true)
  @Transactional
  public Dtos.CaseResponse transitionState(UUID caseId, CaseState targetState, String note, String actor, String correlationId) {
    DisputeCaseEntity e = caseRepository.findById(caseId)
        .orElseThrow(() -> new DisputeException.NotFound("Case not found: " + caseId));

    CaseState prev = e.getState();
    if (!CaseStateTransitions.canTransition(prev, targetState)) {
      throw new DisputeException.BadRequest("Invalid transition " + prev + " -> " + targetState);
    }

    DisputeCaseEntity before = copyForAudit(e);

    e.setState(targetState);
    Instant now = Instant.now();
    e.setLastUpdatedAt(now);
    e.setLastUpdatedBy(actor);

    var deadline = deadlineProperties.deadlineFor(targetState.name());
    e.setDueAt(deadline == null ? null : now.plus(deadline));

    if (targetState == CaseState.CLOSED) {
      e.setClosedAt(now);
    }

    caseRepository.save(e);

    auditService.record(caseId, actor, "STATE_CHANGED", auditSafe(before), auditSafe(e), note, correlationId);
    eventPublisher.publish(e.getExternalRef(), new DisputeEvent(caseId, e.getExternalRef(), e.getState(), e.getAssignedTeam(), "STATE_CHANGED", Instant.now()));

    return toResponse(e, attachmentRepository.findByCaseIdOrderByUploadedAtDesc(caseId), false);
  }

  /**
   * Assign case to new team (admin only at controller).
   */
  @CacheEvict(cacheNames = "cases", allEntries = true)
  @Transactional
  public Dtos.CaseResponse assignTeam(UUID caseId, CaseTeam team, String note, String actor, String correlationId) {
    DisputeCaseEntity e = caseRepository.findById(caseId)
        .orElseThrow(() -> new DisputeException.NotFound("Case not found: " + caseId));
    DisputeCaseEntity before = copyForAudit(e);

    e.setAssignedTeam(team);
    e.setLastUpdatedAt(Instant.now());
    e.setLastUpdatedBy(actor);
    caseRepository.save(e);

    auditService.record(caseId, actor, "TEAM_ASSIGNED", auditSafe(before), auditSafe(e), note, correlationId);
    eventPublisher.publish(e.getExternalRef(), new DisputeEvent(caseId, e.getExternalRef(), e.getState(), e.getAssignedTeam(), "TEAM_ASSIGNED", Instant.now()));
    return toResponse(e, attachmentRepository.findByCaseIdOrderByUploadedAtDesc(caseId), false);
  }

  /**
   * Updates encrypted PII envelope.
   *
   * <p>Controller must enforce {@code ROLE_DISPUTE_PII_VIEW} (or admin) + edit access.</p>
   */
  @CacheEvict(cacheNames = "cases", allEntries = true)
  @Transactional
  public Dtos.CaseResponse updatePii(UUID caseId, Dtos.CustomerPii pii, String note, String actor, String correlationId) {
    DisputeCaseEntity e = caseRepository.findById(caseId)
        .orElseThrow(() -> new DisputeException.NotFound("Case not found: " + caseId));
    DisputeCaseEntity before = copyForAudit(e);

    e.setPiiEnvelope(piiCryptoService.encrypt(caseId, pii));
    e.setPiiKid(pii == null ? null : piiCryptoService.primaryKid());
    e.setLastUpdatedAt(Instant.now());
    e.setLastUpdatedBy(actor);
    caseRepository.save(e);

    auditService.record(caseId, actor, "PII_UPDATED", auditSafe(before), auditSafe(e), note, correlationId);
    eventPublisher.publish(e.getExternalRef(), new DisputeEvent(caseId, e.getExternalRef(), e.getState(), e.getAssignedTeam(), "PII_UPDATED", Instant.now()));
    return toResponse(e, attachmentRepository.findByCaseIdOrderByUploadedAtDesc(caseId), false);
  }


  /**
   * Enables/disables legal hold for a case (prevents retention purge of PII).
   */
  @CacheEvict(cacheNames = "cases", allEntries = true)
  @Transactional
  public Dtos.CaseResponse setLegalHold(UUID caseId, boolean legalHold, String note, String actor, String correlationId) {
    DisputeCaseEntity e = caseRepository.findById(caseId)
        .orElseThrow(() -> new DisputeException.NotFound("Case not found: " + caseId));
    DisputeCaseEntity before = copyForAudit(e);

    e.setLegalHold(legalHold);
    e.setLastUpdatedAt(Instant.now());
    e.setLastUpdatedBy(actor);
    caseRepository.save(e);

    auditService.record(caseId, actor, "LEGAL_HOLD_CHANGED", auditSafe(before), auditSafe(e), note, correlationId);
    eventPublisher.publish(e.getExternalRef(), new DisputeEvent(caseId, e.getExternalRef(), e.getState(), e.getAssignedTeam(), "LEGAL_HOLD_CHANGED", Instant.now()));
    return toResponse(e, attachmentRepository.findByCaseIdOrderByUploadedAtDesc(caseId), false);
  }

  /**
   * Registers attachment metadata (legacy flow) - client uploads binary out-of-band.
   */
  @CacheEvict(cacheNames = "cases", allEntries = true)
  @Transactional
  public Dtos.AttachmentMetadata registerAttachment(UUID caseId, Dtos.RegisterAttachmentRequest req, String actor, String correlationId) {
    DisputeCaseEntity c = caseRepository.findById(caseId)
        .orElseThrow(() -> new DisputeException.NotFound("Case not found: " + caseId));

    DisputeAttachmentEntity a = new DisputeAttachmentEntity();
    a.setId(UUID.randomUUID());
    a.setCaseId(caseId);
    a.setFilename(req.filename());
    a.setContentType(req.contentType());
    a.setSizeBytes(req.sizeBytes());
    a.setSha256(req.sha256().toLowerCase());
    a.setUploadedAt(Instant.now());
    a.setUploadedBy(actor);

    String storageKey = attachmentProperties.storagePrefix() + caseId + "/" + a.getId() + "/" + sanitize(req.filename());
    a.setStorageKey(storageKey);

    attachmentRepository.save(a);

    auditService.record(caseId, actor, "ATTACHMENT_REGISTERED", null, null, "Registered attachment metadata: " + a.getFilename(), correlationId);
    eventPublisher.publish(c.getExternalRef(), new DisputeEvent(caseId, c.getExternalRef(), c.getState(), c.getAssignedTeam(), "ATTACHMENT_REGISTERED", Instant.now()));

    return toAttachmentDto(a);
  }

  /**
   * Preferred attachment flow: generates presigned URL AND stores metadata in a single transaction.
   */
  @CacheEvict(cacheNames = "cases", allEntries = true)
  @Transactional
  public Dtos.PresignUploadResponse presignUpload(UUID caseId, Dtos.PresignUploadRequest req, String actor, String correlationId) {
    DisputeCaseEntity c = caseRepository.findById(caseId)
        .orElseThrow(() -> new DisputeException.NotFound("Case not found: " + caseId));

    DisputeAttachmentEntity a = new DisputeAttachmentEntity();
    a.setId(UUID.randomUUID());
    a.setCaseId(caseId);
    a.setFilename(req.filename());
    a.setContentType(req.contentType());
    a.setSizeBytes(req.sizeBytes());
    a.setSha256(req.sha256().toLowerCase());
    a.setUploadedAt(Instant.now());
    a.setUploadedBy(actor);

    String storageKey = attachmentProperties.storagePrefix() + caseId + "/" + a.getId() + "/" + sanitize(req.filename());
    a.setStorageKey(storageKey);

    // Persist metadata first to ensure auditability.
    attachmentRepository.save(a);

    var presigned = secureStorageService.presignUpload(storageKey, req.contentType(), req.sizeBytes());
    auditService.record(caseId, actor, "ATTACHMENT_PRESIGNED_UPLOAD", null, null, "Presigned upload for: " + a.getFilename(), correlationId);

    eventPublisher.publish(c.getExternalRef(), new DisputeEvent(caseId, c.getExternalRef(), c.getState(), c.getAssignedTeam(), "ATTACHMENT_PRESIGNED_UPLOAD", Instant.now()));

    return new Dtos.PresignUploadResponse(a.getId(), storageKey, presigned.url(), presigned.expiresAt(), presigned.headers());
  }

  /**
   * Generates a presigned download URL for an existing attachment metadata entry.
   */
  @Transactional(readOnly = true)
  public Dtos.PresignDownloadResponse presignDownload(UUID caseId, UUID attachmentId) {
    DisputeAttachmentEntity a = attachmentRepository.findByIdAndCaseId(attachmentId, caseId)
        .orElseThrow(() -> new DisputeException.NotFound("Attachment not found: " + attachmentId));
    var presigned = secureStorageService.presignDownload(a.getStorageKey());
    return new Dtos.PresignDownloadResponse(a.getId(), a.getStorageKey(), presigned.url(), presigned.expiresAt());
  }

  /**
   * Marks a case as deadline breached exactly once (idempotent). Used by scheduler.
   */
  @CacheEvict(cacheNames = "cases", allEntries = true)
  @Transactional
  public void markDeadlineBreached(UUID caseId, String systemActor, String correlationId) {
    DisputeCaseEntity e = caseRepository.findById(caseId)
        .orElseThrow(() -> new DisputeException.NotFound("Case not found: " + caseId));
    if (e.isDeadlineBreached()) return;

    e.setDeadlineBreached(true);
    e.setLastUpdatedAt(Instant.now());
    e.setLastUpdatedBy(systemActor);
    caseRepository.save(e);

    auditService.record(caseId, systemActor, "DEADLINE_BREACHED", null, null, "Deadline breached", correlationId);
    eventPublisher.publish(e.getExternalRef(), new DisputeEvent(caseId, e.getExternalRef(), e.getState(), e.getAssignedTeam(), "DEADLINE_BREACHED", Instant.now()));
  }

  private Dtos.CaseResponse toResponse(DisputeCaseEntity e, List<DisputeAttachmentEntity> atts, boolean includePii) {
    Dtos.CustomerPii pii = includePii ? piiCryptoService.decrypt(e.getId(), e.getPiiEnvelope()) : null;

    return new Dtos.CaseResponse(
        e.getId(),
        e.getExternalRef(),
        e.getCustomerRefHash(),
        e.getAmountCents(),
        e.getCurrency(),
        e.getState(),
        e.getAssignedTeam(),
        e.getOpenedAt(),
        e.getDueAt(),
        e.getClosedAt(),
        e.isLegalHold(),
        e.getLastUpdatedAt(),
        e.getLastUpdatedBy(),
        e.getDescription(),
        pii,
        atts.stream().map(this::toAttachmentDto).toList()
    );
  }

  private Dtos.AttachmentMetadata toAttachmentDto(DisputeAttachmentEntity a) {
    return new Dtos.AttachmentMetadata(
        a.getId(),
        a.getStorageKey(),
        a.getFilename(),
        a.getContentType(),
        a.getSizeBytes(),
        a.getSha256(),
        a.getUploadedAt(),
        a.getUploadedBy()
    );
  }

  private String sanitize(String filename) {
    return filename.replaceAll("[^a-zA-Z0-9._-]", "_");
  }

  private DisputeCaseEntity copyForAudit(DisputeCaseEntity e) {
    DisputeCaseEntity c = new DisputeCaseEntity();
    c.setId(e.getId());
    c.setVersion(e.getVersion());
    c.setExternalRef(e.getExternalRef());
    c.setCustomerRefHash(e.getCustomerRefHash());
    c.setPiiEnvelope(e.getPiiEnvelope());
    c.setAmountCents(e.getAmountCents());
    c.setCurrency(e.getCurrency());
    c.setState(e.getState());
    c.setAssignedTeam(e.getAssignedTeam());
    c.setOpenedAt(e.getOpenedAt());
    c.setDueAt(e.getDueAt());
    c.setClosedAt(e.getClosedAt());
    c.setLegalHold(e.isLegalHold());
    c.setDeadlineBreached(e.isDeadlineBreached());
    c.setLastUpdatedAt(e.getLastUpdatedAt());
    c.setLastUpdatedBy(e.getLastUpdatedBy());
    c.setDescription(e.getDescription());
    return c;
  }

  /**
   * Produces an audit-safe copy of the entity where encrypted PII payload is removed.
   */
  private DisputeCaseEntity auditSafe(DisputeCaseEntity e) {
    DisputeCaseEntity c = copyForAudit(e);
    c.setPiiEnvelope(null); // never store encrypted blob in audit snapshots
    return c;
  }
}

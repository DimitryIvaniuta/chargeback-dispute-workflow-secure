package com.github.dimitryivaniuta.gateway.dispute.service;

import com.github.dimitryivaniuta.gateway.dispute.domain.CaseState;
import com.github.dimitryivaniuta.gateway.dispute.domain.CaseTeam;
import java.time.Instant;
import java.util.UUID;

/**
 * Outgoing domain event published to Kafka.
 */
public record DisputeEvent(
    UUID caseId,
    String externalRef,
    CaseState state,
    CaseTeam team,
    String type,
    Instant occurredAt
) {}

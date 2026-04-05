package com.github.dimitryivaniuta.gateway.dispute.storage;

import java.time.Instant;

/**
 * Result of a presigned download URL generation.
 */
public record PresignedDownload(
    String url,
    Instant expiresAt
) {}

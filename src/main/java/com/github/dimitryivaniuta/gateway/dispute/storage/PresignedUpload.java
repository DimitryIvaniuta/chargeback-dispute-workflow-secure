package com.github.dimitryivaniuta.gateway.dispute.storage;

import java.time.Instant;
import java.util.Map;

/**
 * Result of a presigned upload URL generation.
 */
public record PresignedUpload(
    String url,
    Instant expiresAt,
    Map<String, String> headers
) {}

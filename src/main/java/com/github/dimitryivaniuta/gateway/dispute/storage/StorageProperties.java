package com.github.dimitryivaniuta.gateway.dispute.storage;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Secure attachment storage configuration.
 *
 * <p>Only attachment metadata is stored in Postgres. Clients upload/download binaries using presigned URLs.</p>
 */
@ConfigurationProperties(prefix = "app.storage.s3")
public record StorageProperties(
    boolean enabled,
    String bucket,
    String region,
    URI endpoint,
    Duration presignExpiry
) {}

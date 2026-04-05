package com.github.dimitryivaniuta.gateway.dispute.pii;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for PII encryption and retention.
 */
@ConfigurationProperties(prefix = "app.pii")
public record PiiProperties(
    /**
     * Legacy Base64 encoded 256-bit master key for envelope encryption (local/dev only).
     *
     * <p>If {@link #keys()} is empty, this key is used under the implicit id {@code legacy}.</p>
     */
    String masterKeyBase64,

    /**
     * Primary key id to use for encryption when {@link #keys()} is configured.
     */
    String primaryKeyId,

    /**
     * Optional key ring for envelope encryption (preferred). Multiple enabled keys can be configured
     * to support decryption of historical records during rotation.
     */
    List<PiiKeyConfig> keys,

    /**
     * How long after case closure PII may remain stored (unless legal hold is enabled).
     */
    Duration retentionAfterClose
) {

  /**
   * Single key definition.
   *
   * @param id stable key id used in envelopes
   * @param keyBase64 Base64 encoded 32-byte AES key
   * @param enabled whether this key may be used for decrypt/encrypt
   */
  public record PiiKeyConfig(String id, String keyBase64, boolean enabled) {}
}

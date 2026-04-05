package com.github.dimitryivaniuta.gateway.dispute.pii;

/**
 * Stored envelope format (JSON) for encrypted PII.
 *
 * <p>This structure is serialized to JSON and then Base64-encoded for DB storage.</p>
 */
public record PiiEnvelope(
    int v,
    String kid,
    EnvelopePart ek,   // encrypted data key
    EnvelopePart data  // encrypted payload
) {
  public record EnvelopePart(String nonceB64, String cipherB64) {}
}

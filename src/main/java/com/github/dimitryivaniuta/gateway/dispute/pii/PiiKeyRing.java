package com.github.dimitryivaniuta.gateway.dispute.pii;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/**
 * In-memory key ring for envelope encryption with operational rotation support.
 *
 * <p>Keys are loaded from configuration at startup, but operators may (admin-only) generate
 * key material, add it to the running ring, promote it to primary, and optionally schedule
 * deprecation of the previous primary key with a grace period.</p>
 *
 * <p><b>Important:</b> Runtime operations do not persist configuration. The runbook endpoint
 * returns a config snippet so operators can apply changes to their secret storage/config and
 * redeploy.</p>
 */
@Component
public class PiiKeyRing {

  private static final SecureRandom RNG = new SecureRandom();

  private final ConcurrentMap<String, KeyEntry> keysById = new ConcurrentHashMap<>();
  private final AtomicReference<String> primaryId = new AtomicReference<>();

  /**
   * Builds the key ring from {@link PiiProperties}. Supports legacy single-key mode.
   */
  public PiiKeyRing(PiiProperties properties) {
    Objects.requireNonNull(properties, "properties");

    List<PiiProperties.PiiKeyConfig> configured = properties.keys();
    if (configured == null || configured.isEmpty()) {
      // legacy mode
      String b64 = properties.masterKeyBase64();
      if (b64 == null || b64.isBlank()) {
        throw new IllegalStateException("app.pii.master-key-base64 must be configured (or configure app.pii.keys)");
      }
      putKeyInternal("legacy", decodeAes256(b64), true, null);
      primaryId.set("legacy");
    } else {
      for (var k : configured) {
        if (k == null) continue;
        if (k.id() == null || k.id().isBlank()) {
          throw new IllegalStateException("app.pii.keys[].id must be non-empty");
        }
        if (keysById.containsKey(k.id())) {
          throw new IllegalStateException("Duplicate app.pii.keys id: " + k.id());
        }
        putKeyInternal(k.id(), decodeAes256(k.keyBase64()), k.enabled(), null);
      }
      String primary = properties.primaryKeyId();
      if (primary == null || primary.isBlank()) {
        throw new IllegalStateException("app.pii.primary-key-id must be configured when app.pii.keys is set");
      }
      KeyEntry pe = keysById.get(primary);
      if (pe == null) {
        throw new IllegalStateException("Primary key id not found in app.pii.keys: " + primary);
      }
      if (!pe.enabled) {
        throw new IllegalStateException("Primary key is disabled: " + primary);
      }
      primaryId.set(primary);
    }
  }

  /** Returns the current primary key id used for encryption. */
  public String primaryKeyId() {
    return primaryId.get();
  }

  /** Returns the current primary key. */
  public SecretKey primaryKey() {
    return keyById(primaryKeyId());
  }

  /** Returns the key for the given id; requires it to be enabled. */
  public SecretKey keyById(String id) {
    KeyEntry e = keysById.get(id);
    if (e == null) {
      throw new IllegalStateException("Unknown PII master key id: " + id);
    }
    if (!e.enabled) {
      throw new IllegalStateException("PII master key is disabled: " + id);
    }
    return e.key;
  }

  /** Returns a read-only view of configured keys and their status (no key material). */
  public Map<String, KeyStatus> list() {
    Map<String, KeyStatus> out = new LinkedHashMap<>();
    String p = primaryKeyId();
    for (var e : keysById.values()) {
      out.put(e.id, new KeyStatus(e.id, e.enabled, e.id.equals(p), e.deprecatedUntil));
    }
    return Map.copyOf(out);
  }

  /**
   * Adds a new enabled/disabled key to the running ring.
   *
   * <p>This is an operational action; it does not persist configuration changes.</p>
   */
  public void addKey(String id, String keyBase64, boolean enabled) {
    if (id == null || id.isBlank()) throw new IllegalArgumentException("id must be non-empty");
    SecretKey key = decodeAes256(keyBase64);
    KeyEntry prev = keysById.putIfAbsent(id, new KeyEntry(id, key, enabled, null));
    if (prev != null) {
      throw new IllegalArgumentException("Key id already exists: " + id);
    }
  }

  /**
   * Promotes an enabled key id to be the primary encryption key.
   *
   * <p>This is an operational action; it does not persist configuration changes.</p>
   */
  public void promote(String id) {
    KeyEntry e = keysById.get(id);
    if (e == null) throw new IllegalArgumentException("Unknown key id: " + id);
    if (!e.enabled) throw new IllegalArgumentException("Key is disabled: " + id);
    primaryId.set(id);
  }

  /** Schedules deprecation of a key: it remains enabled until {@code now + gracePeriod}. */
  public Deprecation deprecate(String id, Duration gracePeriod) {
    if (gracePeriod == null || gracePeriod.isNegative() || gracePeriod.isZero()) {
      throw new IllegalArgumentException("gracePeriod must be positive");
    }
    KeyEntry e = keysById.get(id);
    if (e == null) throw new IllegalArgumentException("Unknown key id: " + id);
    Instant until = Instant.now().plus(gracePeriod);
    e.deprecatedUntil = until;
    return new Deprecation(id, until);
  }

  /** Disables a key immediately (cannot be used for decrypt). */
  public void disableNow(String id) {
    KeyEntry e = keysById.get(id);
    if (e == null) throw new IllegalArgumentException("Unknown key id: " + id);
    if (id.equals(primaryKeyId())) {
      throw new IllegalArgumentException("Cannot disable primary key: " + id);
    }
    e.enabled = false;
  }

  /** Expires deprecated keys whose grace period elapsed (excluding current primary). */
  public ExpireResult expireDeprecatedKeys() {
    int disabled = 0;
    String p = primaryKeyId();
    Instant now = Instant.now();
    for (KeyEntry e : keysById.values()) {
      if (!e.enabled) continue;
      if (e.deprecatedUntil == null) continue;
      if (e.id.equals(p)) continue;
      if (!now.isBefore(e.deprecatedUntil)) {
        e.enabled = false;
        disabled++;
      }
    }
    return new ExpireResult(disabled);
  }

  /** Validates ring health: primary exists + enabled. */
  public Health health() {
    String p = primaryKeyId();
    KeyEntry pe = keysById.get(p);
    if (pe == null) return new Health(false, "primary key missing: " + p);
    if (!pe.enabled) return new Health(false, "primary key disabled: " + p);
    return new Health(true, "OK");
  }

  /** Generates a new random 256-bit AES key (Base64 encoded). */
  public GeneratedKey generateKeyMaterial() {
    byte[] raw = new byte[32];
    RNG.nextBytes(raw);
    return new GeneratedKey(Base64.getEncoder().encodeToString(raw));
  }

  /**
   * Returns a YAML snippet operators can apply to configuration/secrets.
   *
   * <p>Existing keys are included as placeholders (operators should keep their existing secrets).</p>
   */
  public String buildConfigSnippetForRotation(String newPrimaryId, String newKeyBase64, String previousPrimaryId, Duration gracePeriod) {
    String grace = (gracePeriod == null) ? "" : gracePeriod.toString();
    String nl = "\n";
    return "# --- Apply this snippet to your configuration/secrets and redeploy ---" + nl +
        "app:" + nl +
        "  pii:" + nl +
        "    primary-key-id: \"" + newPrimaryId + "\"" + nl +
        "    keys:" + nl +
        "      # Keep existing keys (required to decrypt historical envelopes)" + nl +
        "      # - id: \"" + previousPrimaryId + "\"" + nl +
        "      #   key-base64: \"<keep existing secret>\"" + nl +
        "      #   enabled: true" + nl +
        "      - id: \"" + newPrimaryId + "\"" + nl +
        "        key-base64: \"" + newKeyBase64 + "\"" + nl +
        "        enabled: true" + nl +
        "    # Optional: deprecate previous primary after a grace period (operational only)" + nl +
        "    # previous-primary-id: \"" + previousPrimaryId + "\"" + nl +
        "    # deprecate-after: \"" + grace + "\"" + nl;
  }

  private void putKeyInternal(String id, SecretKey key, boolean enabled, Instant deprecatedUntil) {
    keysById.put(id, new KeyEntry(id, key, enabled, deprecatedUntil));
  }

  private static SecretKey decodeAes256(String b64) {
    if (b64 == null || b64.isBlank()) {
      throw new IllegalStateException("Key material must be Base64-encoded 32 bytes");
    }
    byte[] raw = Base64.getDecoder().decode(b64);
    if (raw.length != 32) {
      throw new IllegalStateException("Key must be 32 bytes (256-bit) after Base64 decode");
    }
    return AesGcm.keyFromBytes(raw);
  }

  private static final class KeyEntry {
    final String id;
    final SecretKey key;
    volatile boolean enabled;
    volatile Instant deprecatedUntil;

    private KeyEntry(String id, SecretKey key, boolean enabled, Instant deprecatedUntil) {
      this.id = id;
      this.key = key;
      this.enabled = enabled;
      this.deprecatedUntil = deprecatedUntil;
    }
  }

  /** Status view for operational endpoints. */
  public record KeyStatus(String id, boolean enabled, boolean primary, Instant deprecatedUntil) {}

  /** Ring health information. */
  public record Health(boolean ok, String message) {}

  /** Generated key material response. */
  public record GeneratedKey(String keyBase64) {}

  /** Deprecation schedule response. */
  public record Deprecation(String keyId, Instant disabledAt) {}

  /** Result of expiring deprecated keys. */
  public record ExpireResult(int disabledKeys) {}
}

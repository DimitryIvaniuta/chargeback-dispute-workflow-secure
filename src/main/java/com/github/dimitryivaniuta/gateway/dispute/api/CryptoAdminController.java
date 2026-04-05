package com.github.dimitryivaniuta.gateway.dispute.api;

import com.github.dimitryivaniuta.gateway.dispute.pii.PiiKeyRing;
import com.github.dimitryivaniuta.gateway.dispute.service.PiiReencryptJobService;
import com.github.dimitryivaniuta.gateway.dispute.support.ExportSigningService;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Operational crypto tooling for key management.
 *
 * <p>These endpoints are admin-only and intended for runbook workflows (key rotation, health checks, key generation).</p>
 */
@RestController
@RequestMapping("/api/admin/crypto")
@RequiredArgsConstructor
public class CryptoAdminController {

  private final PiiKeyRing piiKeyRing;
  private final ExportSigningService exportSigningService;
  private final PiiReencryptJobService piiReencryptJobService;

  /**
   * Lists PII master keys configured for envelope encryption (no key material returned).
   */
  @GetMapping("/pii-keys")
  @PreAuthorize("hasAuthority('ROLE_DISPUTE_ADMIN')")
  public Map<String, PiiKeyRing.KeyStatus> listPiiKeys() {
    return piiKeyRing.list();
  }

  /**
   * Validates ring health: primary exists and enabled.
   */
  @GetMapping("/pii-keys/health")
  @PreAuthorize("hasAuthority('ROLE_DISPUTE_ADMIN')")
  public PiiKeyRing.Health piiKeysHealth() {
    return piiKeyRing.health();
  }

  /**
   * Promotes an enabled key to be the primary key used for new envelope encryption.
   */
  @PostMapping("/pii-keys/{keyId}/promote")
  @PreAuthorize("hasAuthority('ROLE_DISPUTE_ADMIN')")
  public Map<String, String> promote(@PathVariable String keyId) {
    piiKeyRing.promote(keyId);
    return Map.of("primaryKeyId", piiKeyRing.primaryKeyId());
  }

  /**
   * Schedules deprecation of a key with a grace period. The key remains enabled for decrypt until it expires.
   */
  @PostMapping("/pii-keys/{keyId}/deprecate")
  @PreAuthorize("hasAuthority('ROLE_DISPUTE_ADMIN')")
  public PiiKeyRing.Deprecation deprecate(
      @PathVariable String keyId,
      @RequestParam(defaultValue = "P30D") Duration gracePeriod
  ) {
    return piiKeyRing.deprecate(keyId, gracePeriod);
  }

  /**
   * Generates new PII master key material (AES-256) as Base64.
   *
   * <p>NOTE: This endpoint does not persist configuration changes. Operators should add the generated key
   * to secret storage/config and then restart or redeploy with updated configuration. Promotion can be done at runtime.</p>
   */
  @PostMapping("/pii-keys/generate")
  @PreAuthorize("hasAuthority('ROLE_DISPUTE_ADMIN')")
  public PiiKeyRing.GeneratedKey generatePiiKey() {
    return piiKeyRing.generateKeyMaterial();
  }

  /**
   * "Rotate key" runbook endpoint:
   * generate → validate → output config snippet → promote (optional) → deprecate old primary (optional).
   *
   * <p>This endpoint is safe by default: it generates material and prints the configuration snippet without
   * changing the running primary unless {@code promoteImmediately=true}.</p>
   */
  @PostMapping("/pii-keys/rotate-runbook")
  @PreAuthorize("hasAuthority('ROLE_DISPUTE_ADMIN')")
  public RotateRunbookResponse rotateRunbook(@RequestBody RotateRunbookRequest req) {
    String prevPrimary = piiKeyRing.primaryKeyId();

    String newKeyId = (req.newKeyId == null || req.newKeyId.isBlank())
        ? ("k" + Instant.now().toString().replace(":", "").replace("-", "").replace(".", ""))
        : req.newKeyId.trim();

    var material = piiKeyRing.generateKeyMaterial();
    // Add to running ring so promotion can happen without restart (still requires persisting config afterwards).
    piiKeyRing.addKey(newKeyId, material.keyBase64(), true);

    var health = piiKeyRing.health();
    if (!health.ok()) {
      throw new IllegalStateException("Key ring is unhealthy: " + health.message());
    }

    boolean promoted = false;
    if (req.promoteImmediately) {
      piiKeyRing.promote(newKeyId);
      promoted = true;
    }

    PiiKeyRing.Deprecation dep = null;
    if (req.deprecateOldPrimary) {
      dep = piiKeyRing.deprecate(prevPrimary, req.deprecationGracePeriod);
    }

    String snippet = piiKeyRing.buildConfigSnippetForRotation(
        promoted ? piiKeyRing.primaryKeyId() : newKeyId,
        material.keyBase64(),
        prevPrimary,
        req.deprecationGracePeriod
    );

    List<String> steps = List.of(
        "1) Store the generated key material in your secret manager under id '" + newKeyId + "'.",
        "2) Apply the provided YAML snippet to configuration (keep existing keys to decrypt historical envelopes).",
        promoted ? "3) Primary key was promoted at runtime to '" + newKeyId + "'."
                 : "3) (Optional) Promote at runtime using POST /api/admin/crypto/pii-keys/" + newKeyId + "/promote",
        req.deprecateOldPrimary
            ? "4) Previous primary '" + prevPrimary + "' scheduled for disable at " + dep.disabledAt() + "."
            : "4) (Optional) Deprecate old primary with POST /api/admin/crypto/pii-keys/" + prevPrimary + "/deprecate?gracePeriod=P30D",
        "5) Redeploy/restart with updated config to make rotation persistent."
    );

    return new RotateRunbookResponse(
        prevPrimary,
        newKeyId,
        material.keyBase64(),
        promoted ? piiKeyRing.primaryKeyId() : prevPrimary,
        promoted,
        dep == null ? null : dep.disabledAt(),
        snippet,
        steps
    );
  }


  /**
   * Schedules a background job to re-encrypt PII envelopes from one kid to another (throttled).
   *
   * <p>Use this after rotating/promoting a new primary key to gradually re-wrap historical envelopes.</p>
   */
  @PostMapping("/pii-keys/reencrypt-jobs")
  @PreAuthorize("hasAuthority('ROLE_DISPUTE_ADMIN')")
  public Dtos.ReencryptJobResponse scheduleReencrypt(@RequestBody Dtos.ReencryptPiiRequest req,
                                                    org.springframework.security.core.Authentication auth) {
    return piiReencryptJobService.schedule(req, auth.getName());
  }

  /** Returns status/progress of a re-encryption job. */
  @GetMapping("/pii-keys/reencrypt-jobs/{jobId}")
  @PreAuthorize("hasAuthority('ROLE_DISPUTE_ADMIN')")
  public Dtos.ReencryptJobResponse getReencrypt(@PathVariable java.util.UUID jobId) {
    return piiReencryptJobService.get(jobId);
  }

  /** Requests cancellation of a running/pending re-encryption job. */
  @PostMapping("/pii-keys/reencrypt-jobs/{jobId}/cancel")
  @PreAuthorize("hasAuthority('ROLE_DISPUTE_ADMIN')")
  public Dtos.ReencryptJobResponse cancelReencrypt(@PathVariable java.util.UUID jobId) {
    return piiReencryptJobService.cancel(jobId);
  }


  /**
   * Returns current export signing public key metadata.
   */
  @GetMapping("/export-signing")
  @PreAuthorize("hasAuthority('ROLE_DISPUTE_ADMIN')")
  public Dtos.ExportSigningPublicKeyResponse currentExportSigningKey() {
    return new Dtos.ExportSigningPublicKeyResponse(
        exportSigningService.algorithm(),
        exportSigningService.getKeyId(),
        exportSigningService.getPublicKeyX509Base64()
    );
  }

  /**
   * Generates a new Ed25519 key pair for export signing.
   *
   * <p>Returns key material so it can be stored in a secret manager and applied via configuration.
   * This endpoint does not update the running key.</p>
   */
  @PostMapping("/export-signing/generate")
  @PreAuthorize("hasAuthority('ROLE_DISPUTE_ADMIN')")
  public Map<String, String> generateExportSigningKeypair() {
    try {
      KeyPairGenerator g = KeyPairGenerator.getInstance("Ed25519");
      KeyPair kp = g.generateKeyPair();
      String priv = java.util.Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
      String pub = java.util.Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
      return Map.of(
          "algorithm", "Ed25519",
          "privateKeyPkcs8Base64", priv,
          "publicKeyX509Base64", pub
      );
    } catch (Exception e) {
      throw new IllegalStateException("Failed to generate export signing keypair", e);
    }
  }

  /**
   * Request payload for rotate-runbook.
   */
  public record RotateRunbookRequest(
      String newKeyId,
      boolean promoteImmediately,
      boolean deprecateOldPrimary,
      Duration deprecationGracePeriod
  ) {
    public RotateRunbookRequest {
      if (deprecationGracePeriod == null) deprecationGracePeriod = Duration.ofDays(30);
    }
  }

  /**
   * Response payload for rotate-runbook.
   */
  public record RotateRunbookResponse(
      String previousPrimaryKeyId,
      String newKeyId,
      String newKeyBase64,
      String primaryKeyIdNow,
      boolean promotedAtRuntime,
      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant oldPrimaryDisabledAt,
      String configSnippetYaml,
      List<String> steps
  ) {}
}

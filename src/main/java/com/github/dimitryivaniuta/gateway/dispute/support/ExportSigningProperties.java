package com.github.dimitryivaniuta.gateway.dispute.support;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for auditor export signing.
 */
@ConfigurationProperties(prefix = "app.export.signing")
public record ExportSigningProperties(
    /**
     * Ed25519 private key in PKCS#8 Base64 (no PEM headers). Recommended to store in a secret manager.
     */
    String privateKeyPkcs8Base64,

    /**
     * Ed25519 public key in X.509 SubjectPublicKeyInfo Base64 (no PEM headers).
     */
    String publicKeyX509Base64
) {}

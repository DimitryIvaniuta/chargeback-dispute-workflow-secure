package com.github.dimitryivaniuta.gateway.dispute.support;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Signs auditor exports for tamper-evident integrity checks.
 *
 * <p>Algorithm: Ed25519 (JCA "Ed25519").</p>
 * <p>In production, load keys from a secret manager/KMS and rotate periodically.</p>
 */
@Component
public class ExportSigningService {

  private static final Logger log = LoggerFactory.getLogger(ExportSigningService.class);

  private final PrivateKey privateKey;
  @Getter
  private final PublicKey publicKey;
  @Getter
  private final String publicKeyX509Base64;
  @Getter
  private final String keyId;

  public ExportSigningService(ExportSigningProperties props) {
    try {
      KeyPair kp;
      if (props != null && notBlank(props.privateKeyPkcs8Base64()) && notBlank(props.publicKeyX509Base64())) {
        kp = new KeyPair(
            decodePublic(props.publicKeyX509Base64()),
            decodePrivate(props.privateKeyPkcs8Base64())
        );
      } else {
        kp = generateDevKeyPair();
        log.warn("Export signing keys are not configured; generated an in-memory DEV keypair. Configure app.export.signing.* for production.");
      }

      this.privateKey = kp.getPrivate();
      this.publicKey = kp.getPublic();
      this.publicKeyX509Base64 = Base64.getEncoder().encodeToString(this.publicKey.getEncoded());
      this.keyId = sha256Hex(this.publicKey.getEncoded());
    } catch (Exception e) {
      throw new IllegalStateException("Failed to initialize export signing service", e);
    }
  }

  /** Returns the signing algorithm. */
  public String algorithm() {
    return "Ed25519";
  }

  /** Signs the given bytes; returns Base64(signature). */
  public String sign(byte[] data) {
    try {
      Signature s = Signature.getInstance("Ed25519");
      s.initSign(privateKey);
      s.update(data);
      return Base64.getEncoder().encodeToString(s.sign());
    } catch (Exception e) {
      throw new IllegalStateException("Failed to sign export", e);
    }
  }

  /** Computes SHA-256 digest (raw bytes). */
  public byte[] sha256(byte[] data) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return md.digest(data);
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  private static boolean notBlank(String s) {
    return s != null && !s.isBlank();
  }

  private static PrivateKey decodePrivate(String pkcs8B64) throws Exception {
    byte[] raw = Base64.getDecoder().decode(pkcs8B64);
    return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(raw));
  }

  private static PublicKey decodePublic(String x509B64) throws Exception {
    byte[] raw = Base64.getDecoder().decode(x509B64);
    return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(raw));
  }

  private static KeyPair generateDevKeyPair() throws Exception {
    KeyPairGenerator g = KeyPairGenerator.getInstance("Ed25519");
    return g.generateKeyPair();
  }

  private static String sha256Hex(byte[] data) throws Exception {
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    byte[] d = md.digest(data);
    StringBuilder sb = new StringBuilder(d.length * 2);
    for (byte b : d) sb.append(String.format("%02x", b));
    return sb.toString();
  }
}

package com.github.dimitryivaniuta.gateway.dispute.pii;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimitryivaniuta.gateway.dispute.api.Dtos;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.SecretKey;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Application-layer envelope encryption for PII.
 *
 * <p>Design:</p>
 * <ul>
 *   <li>Generate random data key per envelope</li>
 *   <li>Encrypt payload JSON with data key using AES/GCM</li>
 *   <li>Encrypt data key with master key using AES/GCM</li>
 *   <li>Store both encrypted parts as Base64 JSON</li>
 * </ul>
 *
 * <p>Supports key rotation using a key id ({@code kid}) stored in the envelope.
 * The active key set is provided by {@link PiiKeyRing}.</p>
 */
@Component
@RequiredArgsConstructor
public class PiiCryptoService {

  private static final SecureRandom RNG = new SecureRandom();

  private final ObjectMapper objectMapper;
  private final PiiKeyRing keyRing;

  /** @return current primary master key id used for new encryptions. */
  public String primaryKid() {
    return keyRing.primaryKeyId();
  }

  /**
   * Extracts the {@code kid} from an envelope without decrypting.
   *
   * @return kid or null if envelope is blank or invalid
   */
  public String extractKid(String envelopeB64) {
    if (envelopeB64 == null || envelopeB64.isBlank()) return null;
    try {
      byte[] envJson = Base64.getDecoder().decode(envelopeB64);
      PiiEnvelope env = objectMapper.readValue(envJson, PiiEnvelope.class);
      return env.kid();
    } catch (Exception e) {
      return null;
    }
  }


  /**
   * Encrypts PII for the given case id; returns Base64(JSON(envelope)).
   */
  public String encrypt(UUID caseId, Dtos.CustomerPii pii) {
    if (pii == null) return null;

    try {
      byte[] aad = ("case:" + caseId).getBytes(StandardCharsets.UTF_8);

      // 256-bit data key
      byte[] dataKeyRaw = new byte[32];
      RNG.nextBytes(dataKeyRaw);
      SecretKey dataKey = AesGcm.keyFromBytes(dataKeyRaw);

      // encrypt payload
      byte[] payloadJson = objectMapper.writeValueAsBytes(pii);
      byte[] dataNonce = AesGcm.randomNonce();
      byte[] dataCt = AesGcm.encrypt(payloadJson, dataKey, dataNonce, aad);

      // encrypt data key with current primary master key
      String kid = keyRing.primaryKeyId();
      SecretKey master = keyRing.primaryKey();
      byte[] keyNonce = AesGcm.randomNonce();
      byte[] keyCt = AesGcm.encrypt(dataKeyRaw, master, keyNonce, aad);

      PiiEnvelope env = new PiiEnvelope(
          2,
          kid,
          new PiiEnvelope.EnvelopePart(Base64.getEncoder().encodeToString(keyNonce), Base64.getEncoder().encodeToString(keyCt)),
          new PiiEnvelope.EnvelopePart(Base64.getEncoder().encodeToString(dataNonce), Base64.getEncoder().encodeToString(dataCt))
      );

      byte[] envJson = objectMapper.writeValueAsBytes(env);
      return Base64.getEncoder().encodeToString(envJson);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to encrypt PII", e);
    }
  }


  /**
   * Re-encrypts an existing envelope with a new master key id (kid).
   *
   * <p>This decrypts the payload using the envelope's embedded kid and then re-wraps using {@code newKid}.
   * The payload content is unchanged.</p>
   *
   * @param caseId case id used as AAD
   * @param envelopeB64 Base64(JSON(envelope))
   * @param newKid target master key id (must be present in the key ring)
   * @return new Base64(JSON(envelope)) using {@code newKid}
   */
  public String reencrypt(UUID caseId, String envelopeB64, String newKid) {
    if (envelopeB64 == null || envelopeB64.isBlank()) return null;
    if (newKid == null || newKid.isBlank()) throw new IllegalArgumentException("newKid must not be blank");

    try {
      // decrypt with existing kid
      Dtos.CustomerPii pii = decrypt(caseId, envelopeB64);

      // encrypt with specific master key (not necessarily primary)
      byte[] aad = ("case:" + caseId).getBytes(StandardCharsets.UTF_8);

      // 256-bit data key
      byte[] dataKeyRaw = new byte[32];
      RNG.nextBytes(dataKeyRaw);
      SecretKey dataKey = AesGcm.keyFromBytes(dataKeyRaw);

      // encrypt payload
      byte[] payloadJson = objectMapper.writeValueAsBytes(pii);
      byte[] dataNonce = AesGcm.randomNonce();
      byte[] dataCt = AesGcm.encrypt(payloadJson, dataKey, dataNonce, aad);

      // encrypt data key with requested master key
      SecretKey master = keyRing.keyById(newKid);
      byte[] keyNonce = AesGcm.randomNonce();
      byte[] keyCt = AesGcm.encrypt(dataKeyRaw, master, keyNonce, aad);

      PiiEnvelope env = new PiiEnvelope(
          2,
          newKid,
          new PiiEnvelope.EnvelopePart(Base64.getEncoder().encodeToString(keyNonce), Base64.getEncoder().encodeToString(keyCt)),
          new PiiEnvelope.EnvelopePart(Base64.getEncoder().encodeToString(dataNonce), Base64.getEncoder().encodeToString(dataCt))
      );

      byte[] envJson = objectMapper.writeValueAsBytes(env);
      return Base64.getEncoder().encodeToString(envJson);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to re-encrypt PII", e);
    }
  }

  /**
   * Decrypts PII envelope; returns PII object.
   */
  public Dtos.CustomerPii decrypt(UUID caseId, String envelopeB64) {
    if (envelopeB64 == null || envelopeB64.isBlank()) return null;

    try {
      byte[] aad = ("case:" + caseId).getBytes(StandardCharsets.UTF_8);
      byte[] envJson = Base64.getDecoder().decode(envelopeB64);
      PiiEnvelope env = objectMapper.readValue(envJson, PiiEnvelope.class);

      // Backward compat: if kid missing, assume legacy primary
      String kid = (env.kid() == null || env.kid().isBlank()) ? keyRing.primaryKeyId() : env.kid();
      SecretKey master = keyRing.keyById(kid);

      byte[] keyNonce = Base64.getDecoder().decode(env.ek().nonceB64());
      byte[] keyCt = Base64.getDecoder().decode(env.ek().cipherB64());
      byte[] dataKeyRaw = AesGcm.decrypt(keyCt, master, keyNonce, aad);
      SecretKey dataKey = AesGcm.keyFromBytes(dataKeyRaw);

      byte[] dataNonce = Base64.getDecoder().decode(env.data().nonceB64());
      byte[] dataCt = Base64.getDecoder().decode(env.data().cipherB64());
      byte[] payloadJson = AesGcm.decrypt(dataCt, dataKey, dataNonce, aad);

      return objectMapper.readValue(payloadJson, Dtos.CustomerPii.class);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to decrypt PII (tampered or wrong key)", e);
    }
  }
}

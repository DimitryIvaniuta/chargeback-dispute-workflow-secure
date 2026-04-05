package com.github.dimitryivaniuta.gateway.dispute.pii;

import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Minimal AES/GCM helper.
 */
final class AesGcm {

  private static final SecureRandom RNG = new SecureRandom();
  private static final int NONCE_LEN = 12;
  private static final int TAG_BITS = 128;

  private AesGcm() {}

  static byte[] randomNonce() {
    byte[] n = new byte[NONCE_LEN];
    RNG.nextBytes(n);
    return n;
  }

  static SecretKey keyFromBytes(byte[] raw) {
    return new SecretKeySpec(raw, "AES");
  }

  static byte[] encrypt(byte[] plaintext, SecretKey key, byte[] nonce, byte[] aad) {
    try {
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      GCMParameterSpec spec = new GCMParameterSpec(TAG_BITS, nonce);
      c.init(Cipher.ENCRYPT_MODE, key, spec);
      if (aad != null && aad.length > 0) c.updateAAD(aad);
      return c.doFinal(plaintext);
    } catch (Exception e) {
      throw new IllegalStateException("PII encryption failed", e);
    }
  }

  static byte[] decrypt(byte[] ciphertext, SecretKey key, byte[] nonce, byte[] aad) {
    try {
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      GCMParameterSpec spec = new GCMParameterSpec(TAG_BITS, nonce);
      c.init(Cipher.DECRYPT_MODE, key, spec);
      if (aad != null && aad.length > 0) c.updateAAD(aad);
      return c.doFinal(ciphertext);
    } catch (Exception e) {
      throw new IllegalStateException("PII decryption failed (tampered or wrong key)", e);
    }
  }

  static byte[] concat(byte[] a, byte[] b) {
    byte[] out = Arrays.copyOf(a, a.length + b.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }
}

package com.github.dimitryivaniuta.gateway.dispute.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Utility to compute SHA-256 hashes for GDPR-minimized identifiers.
 */
public final class Sha256 {

  private Sha256() {}

  /**
   * Computes SHA-256 hex string for the given input.
   *
   * @param input input string
   * @return lowercase hex string (64 chars)
   */
  public static String hex(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}

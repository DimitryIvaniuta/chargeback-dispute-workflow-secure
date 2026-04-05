package com.github.dimitryivaniuta.gateway.dispute.service;

import com.github.dimitryivaniuta.gateway.dispute.pii.PiiKeyRing;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background job that disables deprecated PII master keys whose grace period elapsed.
 *
 * <p>Deprecated keys remain enabled for decryption until the configured grace period ends.
 * Once expired, they are disabled to reduce blast radius.</p>
 */
@Component
@EnableScheduling
@RequiredArgsConstructor
public class PiiKeyDeprecationJob {

  private final PiiKeyRing keyRing;

  /**
   * Checks for expired deprecated keys and disables them.
   */
  @Scheduled(fixedDelayString = "${app.pii.deprecation-check-interval-ms:60000}")
  public void run() {
    keyRing.expireDeprecatedKeys();
  }
}

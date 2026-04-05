package com.github.dimitryivaniuta.gateway.dispute.api;

import com.github.dimitryivaniuta.gateway.dispute.support.ExportSigningService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public key publishing endpoints.
 *
 * <p>These endpoints are intended for auditors/validators to verify signed exports.</p>
 */
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicKeysController {

  private final ExportSigningService exportSigningService;

  /**
   * Returns the current public key used to sign auditor exports.
   */
  @GetMapping("/export-signing-key")
  public Dtos.ExportSigningPublicKeyResponse exportSigningKey() {
    return new Dtos.ExportSigningPublicKeyResponse(
        exportSigningService.algorithm(),
        exportSigningService.getKeyId(),
        exportSigningService.getPublicKeyX509Base64()
    );
  }
}

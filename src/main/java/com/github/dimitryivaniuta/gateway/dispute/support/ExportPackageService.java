package com.github.dimitryivaniuta.gateway.dispute.support;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Builds signed ZIP packages for auditor exports.
 *
 * <p>ZIP contents:</p>
 * <ul>
 *   <li>{@code <name>.csv}</li>
 *   <li>{@code <name>.sha256} - SHA-256 digest hex</li>
 *   <li>{@code <name>.sig} - Base64(signature) of raw SHA-256 digest bytes</li>
 *   <li>{@code public-key.base64} - X.509 SPKI Base64 public key</li>
 *   <li>{@code manifest.txt}</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ExportPackageService {

  private final ExportSigningService signing;

  /**
   * Creates a signed ZIP containing the given CSV.
   *
   * @param baseName base file name without extension
   * @param csvBytes CSV bytes
   * @return ZIP bytes
   */
  public byte[] createSignedZip(String baseName, byte[] csvBytes) {
    try {
      byte[] digest = signing.sha256(csvBytes);
      String digestHex = toHex(digest);
      String signatureB64 = signing.sign(digest);

      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      try (ZipOutputStream zos = new ZipOutputStream(baos)) {
        put(zos, baseName + ".csv", csvBytes);
        put(zos, baseName + ".sha256", (digestHex + "\n").getBytes(StandardCharsets.UTF_8));
        put(zos, baseName + ".sig", (signatureB64 + "\n").getBytes(StandardCharsets.UTF_8));
        put(zos, "public-key.base64", (signing.getPublicKeyX509Base64() + "\n").getBytes(StandardCharsets.UTF_8));

        String manifest = "export=" + baseName + ".csv\n"
            + "createdAt=" + Instant.now() + "\n"
            + "algorithm=" + signing.algorithm() + "\n"
            + "keyId=" + signing.getKeyId() + "\n"
            + "digest=SHA-256\n"
            + "signature=Base64(Ed25519(digestBytes))\n";
        put(zos, "manifest.txt", manifest.getBytes(StandardCharsets.UTF_8));
      }
      return baos.toByteArray();
    } catch (Exception e) {
      throw new IllegalStateException("Failed to create signed export ZIP", e);
    }
  }

  private static void put(ZipOutputStream zos, String name, byte[] bytes) throws Exception {
    ZipEntry e = new ZipEntry(name);
    zos.putNextEntry(e);
    zos.write(bytes);
    zos.closeEntry();
  }

  private static String toHex(byte[] d) {
    StringBuilder sb = new StringBuilder(d.length * 2);
    for (byte b : d) sb.append(String.format("%02x", b));
    return sb.toString();
  }
}

package com.github.dimitryivaniuta.gateway.dispute.storage;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Local fallback storage service.
 *
 * <p>Returns deterministic dummy URLs to allow end-to-end API testing without real S3.</p>
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.storage.s3", name = "enabled", havingValue = "false", matchIfMissing = true)
public class LocalDummyStorageService implements SecureStorageService {

  private final StorageProperties props;

  @Override
  public PresignedUpload presignUpload(String storageKey, String contentType, long sizeBytes) {
    Instant exp = Instant.now().plus(props.presignExpiry() == null ? java.time.Duration.ofMinutes(15) : props.presignExpiry());
    String url = "http://localhost:8080/dev-storage/upload?key=" + url(storageKey);
    return new PresignedUpload(url, exp, Map.of("Content-Type", contentType));
  }

  @Override
  public PresignedDownload presignDownload(String storageKey) {
    Instant exp = Instant.now().plus(props.presignExpiry() == null ? java.time.Duration.ofMinutes(15) : props.presignExpiry());
    String url = "http://localhost:8080/dev-storage/download?key=" + url(storageKey);
    return new PresignedDownload(url, exp);
  }

  private String url(String v) {
    return URLEncoder.encode(v, StandardCharsets.UTF_8);
  }
}

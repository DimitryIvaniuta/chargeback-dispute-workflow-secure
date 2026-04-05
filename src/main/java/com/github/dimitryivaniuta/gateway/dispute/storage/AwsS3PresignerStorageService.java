package com.github.dimitryivaniuta.gateway.dispute.storage;

import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * S3 presigner implementation (AWS SDK v2).
 *
 * <p>Enable with {@code app.storage.s3.enabled=true}. Credentials are resolved using the default provider chain.</p>
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.storage.s3", name = "enabled", havingValue = "true")
public class AwsS3PresignerStorageService implements SecureStorageService {

  private final StorageProperties props;

  @Override
  public PresignedUpload presignUpload(String storageKey, String contentType, long sizeBytes) {
    try (S3Presigner presigner = presigner()) {
      PutObjectRequest req = PutObjectRequest.builder()
          .bucket(props.bucket())
          .key(storageKey)
          .contentType(contentType)
          .build();

      PutObjectPresignRequest presignReq = PutObjectPresignRequest.builder()
          .signatureDuration(props.presignExpiry())
          .putObjectRequest(req)
          .build();

      PresignedPutObjectRequest p = presigner.presignPutObject(presignReq);
      Instant exp = Instant.now().plus(props.presignExpiry());
      return new PresignedUpload(p.url().toString(), exp, Map.of());
    }
  }

  @Override
  public PresignedDownload presignDownload(String storageKey) {
    try (S3Presigner presigner = presigner()) {
      GetObjectRequest req = GetObjectRequest.builder()
          .bucket(props.bucket())
          .key(storageKey)
          .build();
      PresignedGetObjectRequest p = presigner.presignGetObject(b -> b.signatureDuration(props.presignExpiry()).getObjectRequest(req));
      Instant exp = Instant.now().plus(props.presignExpiry());
      return new PresignedDownload(p.url().toString(), exp);
    }
  }

  private S3Presigner presigner() {
    S3Presigner.Builder b = S3Presigner.builder()
        .credentialsProvider(DefaultCredentialsProvider.create())
        .region(Region.of(props.region()));
    if (props.endpoint() != null) {
      b.endpointOverride(props.endpoint());
    }
    return b.build();
  }
}

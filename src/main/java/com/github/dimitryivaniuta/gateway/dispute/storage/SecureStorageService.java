package com.github.dimitryivaniuta.gateway.dispute.storage;

/**
 * Abstraction over secure object storage (e.g., S3).
 */
public interface SecureStorageService {

  /**
   * Creates a presigned PUT URL for uploading an object.
   *
   * @param storageKey object key
   * @param contentType content type
   * @param sizeBytes size bytes (for validation, may be used in provider policies)
   */
  PresignedUpload presignUpload(String storageKey, String contentType, long sizeBytes);

  /**
   * Creates a presigned GET URL for downloading an object.
   */
  PresignedDownload presignDownload(String storageKey);
}

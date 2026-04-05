package com.github.dimitryivaniuta.gateway.dispute.persistence;

/** Status of a PII envelope re-encryption job. */
public enum PiiReencryptJobStatus {
  PENDING,
  RUNNING,
  COMPLETED,
  FAILED,
  CANCELLED
}

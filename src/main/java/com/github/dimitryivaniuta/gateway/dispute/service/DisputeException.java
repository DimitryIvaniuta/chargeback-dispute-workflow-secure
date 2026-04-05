package com.github.dimitryivaniuta.gateway.dispute.service;

/**
 * Domain-specific exceptions for dispute workflow.
 */
public sealed class DisputeException extends RuntimeException
    permits DisputeException.NotFound, DisputeException.Validation, DisputeException.Conflict, DisputeException.Forbidden {

  protected DisputeException(String message) { super(message); }

  /**
   * 404.
   */
  public static final class NotFound extends DisputeException {
    public NotFound(String message) { super(message); }
  }

  /**
   * 400.
   */
  public static final class Validation extends DisputeException {
    public Validation(String message) { super(message); }
  }

  /**
   * 409.
   */
  public static final class Conflict extends DisputeException {
    public Conflict(String message) { super(message); }
  }

  /**
   * 403.
   */
  public static final class Forbidden extends DisputeException {
    public Forbidden(String message) { super(message); }
  }
}

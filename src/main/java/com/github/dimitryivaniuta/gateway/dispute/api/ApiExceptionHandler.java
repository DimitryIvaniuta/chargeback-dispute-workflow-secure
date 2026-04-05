package com.github.dimitryivaniuta.gateway.dispute.api;

import com.github.dimitryivaniuta.gateway.dispute.service.DisputeException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Central API exception mapping.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler(DisputeException.NotFound.class)
  public ResponseEntity<?> notFound(DisputeException.NotFound ex, HttpServletRequest req) {
    return error(HttpStatus.NOT_FOUND, ex.getMessage(), req);
  }

  @ExceptionHandler({DisputeException.Validation.class, MethodArgumentNotValidException.class})
  public ResponseEntity<?> badRequest(Exception ex, HttpServletRequest req) {
    String msg = ex instanceof MethodArgumentNotValidException manv
        ? (manv.getBindingResult().getFieldErrors().isEmpty() ? "Validation error"
        : manv.getBindingResult().getFieldErrors().get(0).getField() + " " + manv.getBindingResult().getFieldErrors().get(0).getDefaultMessage())
        : ex.getMessage();
    return error(HttpStatus.BAD_REQUEST, msg, req);
  }

  @ExceptionHandler(DisputeException.Conflict.class)
  public ResponseEntity<?> conflict(DisputeException.Conflict ex, HttpServletRequest req) {
    return error(HttpStatus.CONFLICT, ex.getMessage(), req);
  }

  @ExceptionHandler({AccessDeniedException.class, DisputeException.Forbidden.class})
  public ResponseEntity<?> forbidden(Exception ex, HttpServletRequest req) {
    return error(HttpStatus.FORBIDDEN, ex.getMessage(), req);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<?> internal(Exception ex, HttpServletRequest req) {
    return error(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error", req);
  }

  private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message, HttpServletRequest req) {
    return ResponseEntity.status(status).body(Map.of(
        "timestamp", Instant.now().toString(),
        "status", status.value(),
        "error", status.getReasonPhrase(),
        "message", message,
        "path", req.getRequestURI()
    ));
  }
}

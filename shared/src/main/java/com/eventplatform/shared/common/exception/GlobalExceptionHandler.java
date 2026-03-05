package com.eventplatform.shared.common.exception;

import com.eventplatform.shared.stripe.exception.StripeWebhookSignatureException;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(BaseException.class)
  public ResponseEntity<ErrorResponse> handleBaseException(BaseException ex) {
    ErrorResponse response =
        new ErrorResponse(
            ex.getErrorCode(),
            ex.getMessage(),
            ex.getHttpStatus().value(),
            Instant.now(),
            ex.getDetails());
    return ResponseEntity.status(ex.getHttpStatus()).body(response);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
    ErrorResponse response =
        new ErrorResponse("VALIDATION_ERROR", ex.getMessage(), 400, Instant.now(), null);
    return ResponseEntity.badRequest().body(response);
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
    ErrorResponse response =
        new ErrorResponse("ACCESS_DENIED", "Access Denied", 403, Instant.now(), null);
    return ResponseEntity.status(403).body(response);
  }

  @ExceptionHandler(AuthorizationDeniedException.class)
  public ResponseEntity<ErrorResponse> handleAuthorizationDenied(AuthorizationDeniedException ex) {
    ErrorResponse response =
        new ErrorResponse("ACCESS_DENIED", "Access Denied", 403, Instant.now(), null);
    return ResponseEntity.status(403).body(response);
  }

  @ExceptionHandler(AuthenticationException.class)
  public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException ex) {
    ErrorResponse response =
        new ErrorResponse("UNAUTHORIZED", "Unauthorized", 401, Instant.now(), null);
    return ResponseEntity.status(401).body(response);
  }

  @ExceptionHandler(StripeWebhookSignatureException.class)
  public ResponseEntity<ErrorResponse> handleStripeWebhookSignature(
      StripeWebhookSignatureException ex) {
    ErrorResponse response =
        new ErrorResponse(
            "INVALID_WEBHOOK_SIGNATURE", "Invalid webhook signature", 400, Instant.now(), null);
    return ResponseEntity.badRequest().body(response);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
    ErrorResponse response =
        new ErrorResponse("INTERNAL_ERROR", ex.getMessage(), 500, Instant.now(), null);
    return ResponseEntity.status(500).body(response);
  }
}

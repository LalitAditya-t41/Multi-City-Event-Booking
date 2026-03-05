package com.eventplatform.shared.common.exception;

import org.springframework.http.HttpStatus;

public abstract class BaseException extends RuntimeException {
  private final String errorCode;
  private final HttpStatus httpStatus;
  private final Object details;

  protected BaseException(String message, String errorCode, HttpStatus httpStatus) {
    this(message, errorCode, httpStatus, null);
  }

  protected BaseException(String message, String errorCode, HttpStatus httpStatus, Object details) {
    super(message);
    this.errorCode = errorCode;
    this.httpStatus = httpStatus;
    this.details = details;
  }

  public String getErrorCode() {
    return errorCode;
  }

  public HttpStatus getHttpStatus() {
    return httpStatus;
  }

  public Object getDetails() {
    return details;
  }
}

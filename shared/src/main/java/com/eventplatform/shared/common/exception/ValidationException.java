package com.eventplatform.shared.common.exception;

import org.springframework.http.HttpStatus;

public class ValidationException extends BaseException {
  public ValidationException(String message, String errorCode) {
    super(message, errorCode, HttpStatus.BAD_REQUEST);
  }
}

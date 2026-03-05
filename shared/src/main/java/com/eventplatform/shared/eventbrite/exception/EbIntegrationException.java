package com.eventplatform.shared.eventbrite.exception;

import com.eventplatform.shared.common.exception.BaseException;
import org.springframework.http.HttpStatus;

public class EbIntegrationException extends BaseException {
  public EbIntegrationException(String message) {
    super(message, "EB_INTEGRATION_ERROR", HttpStatus.BAD_GATEWAY);
  }

  public EbIntegrationException(String message, Throwable cause) {
    super(message, "EB_INTEGRATION_ERROR", HttpStatus.BAD_GATEWAY);
    initCause(cause);
  }
}

package com.eventplatform.shared.eventbrite.exception;

import com.eventplatform.shared.common.exception.BaseException;
import org.springframework.http.HttpStatus;

public class EbWebhookSignatureException extends BaseException {
  public EbWebhookSignatureException(String message) {
    super(message, "INVALID_SIGNATURE", HttpStatus.UNAUTHORIZED);
  }
}

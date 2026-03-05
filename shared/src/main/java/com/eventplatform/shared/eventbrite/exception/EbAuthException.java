package com.eventplatform.shared.eventbrite.exception;

import com.eventplatform.shared.common.exception.BaseException;
import org.springframework.http.HttpStatus;

public class EbAuthException extends BaseException {
  public EbAuthException(String message) {
    super(message, "EB_AUTH_ERROR", HttpStatus.BAD_GATEWAY);
  }
}

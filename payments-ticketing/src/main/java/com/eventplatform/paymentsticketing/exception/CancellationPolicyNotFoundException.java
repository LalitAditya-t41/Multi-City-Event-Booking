package com.eventplatform.paymentsticketing.exception;

import com.eventplatform.shared.common.exception.BaseException;
import org.springframework.http.HttpStatus;

public class CancellationPolicyNotFoundException extends BaseException {

  public CancellationPolicyNotFoundException(String message) {
    super(message, "CANCELLATION_POLICY_NOT_FOUND", HttpStatus.NOT_FOUND);
  }
}

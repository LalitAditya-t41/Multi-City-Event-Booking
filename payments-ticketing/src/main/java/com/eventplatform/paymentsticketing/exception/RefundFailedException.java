package com.eventplatform.paymentsticketing.exception;

import com.eventplatform.shared.common.exception.IntegrationException;
import java.util.Map;

public class RefundFailedException extends IntegrationException {

  public RefundFailedException(String message, String stripeError) {
    super(message, "REFUND_FAILED", Map.of("stripeError", stripeError));
  }
}

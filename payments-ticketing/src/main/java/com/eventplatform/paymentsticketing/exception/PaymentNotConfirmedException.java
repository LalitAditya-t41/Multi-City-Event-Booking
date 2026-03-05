package com.eventplatform.paymentsticketing.exception;

import com.eventplatform.shared.common.exception.BaseException;
import java.util.Map;
import org.springframework.http.HttpStatus;

public class PaymentNotConfirmedException extends BaseException {

  public PaymentNotConfirmedException(String stripeStatus) {
    super(
        "Payment has not been completed. Please complete payment via Stripe.",
        "PAYMENT_NOT_CONFIRMED",
        HttpStatus.PAYMENT_REQUIRED,
        Map.of("stripeStatus", stripeStatus));
  }
}

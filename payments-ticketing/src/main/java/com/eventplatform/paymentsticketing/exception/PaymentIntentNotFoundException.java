package com.eventplatform.paymentsticketing.exception;

import com.eventplatform.shared.common.exception.ResourceNotFoundException;

public class PaymentIntentNotFoundException extends ResourceNotFoundException {

    public PaymentIntentNotFoundException(String paymentIntentId) {
        super("Payment intent not found: " + paymentIntentId, "PAYMENT_INTENT_NOT_FOUND");
    }
}

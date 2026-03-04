package com.eventplatform.shared.common.service;

public interface PaymentConfirmationReader {
    boolean isPaymentConfirmed(Long cartId);
}

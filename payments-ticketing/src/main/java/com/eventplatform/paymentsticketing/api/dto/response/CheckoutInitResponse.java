package com.eventplatform.paymentsticketing.api.dto.response;

public record CheckoutInitResponse(
    Long cartId,
    String bookingRef,
    String paymentIntentId,
    String clientSecret,
    Long amountInSmallestUnit,
    String currency) {}

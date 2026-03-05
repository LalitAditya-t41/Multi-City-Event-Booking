package com.eventplatform.paymentsticketing.api.dto.request;

import jakarta.validation.constraints.NotBlank;

public record PaymentFailedRequest(@NotBlank String paymentIntentId) {}

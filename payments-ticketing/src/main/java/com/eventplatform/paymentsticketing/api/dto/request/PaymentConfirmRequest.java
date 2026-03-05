package com.eventplatform.paymentsticketing.api.dto.request;

import jakarta.validation.constraints.NotBlank;

public record PaymentConfirmRequest(@NotBlank String paymentIntentId) {}

package com.eventplatform.promotions.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CouponValidateRequest(@NotBlank String couponCode, @NotNull Long cartId) {}

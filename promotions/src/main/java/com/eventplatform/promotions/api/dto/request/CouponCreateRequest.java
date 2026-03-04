package com.eventplatform.promotions.api.dto.request;

import jakarta.validation.constraints.NotBlank;

public record CouponCreateRequest(@NotBlank String code) {
}

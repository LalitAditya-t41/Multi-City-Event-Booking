package com.eventplatform.scheduling.api.dto.request;

import com.eventplatform.scheduling.domain.enums.TierType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

public record PricingTierRequest(
    @NotBlank String name,
    @NotNull @PositiveOrZero BigDecimal priceAmount,
    @NotBlank String currency,
    @NotNull @Positive Integer quota,
    @NotNull TierType tierType
) {
}

package com.eventplatform.scheduling.api.dto.request;

import com.eventplatform.scheduling.domain.enums.TierType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record PricingTierRequest(
    @NotBlank String name,
    @NotNull BigDecimal priceAmount,
    @NotBlank String currency,
    @NotNull Integer quota,
    @NotNull TierType tierType
) {
}

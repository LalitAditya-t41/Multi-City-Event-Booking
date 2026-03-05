package com.eventplatform.promotions.api.dto.request;

import com.eventplatform.promotions.domain.enums.DiscountType;
import com.eventplatform.promotions.domain.enums.PromotionScope;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;

public record PromotionCreateRequest(
    @NotBlank String name,
    @NotNull DiscountType discountType,
    @NotNull @DecimalMin("0.0") BigDecimal discountValue,
    @NotNull PromotionScope scope,
    String ebEventId,
    @NotNull Instant validFrom,
    @NotNull Instant validUntil,
    Integer maxUsageLimit,
    Integer perUserCap) {}

package com.eventplatform.promotions.api.dto.response;

import com.eventplatform.promotions.domain.enums.DiscountType;
import com.eventplatform.promotions.domain.enums.PromotionScope;
import com.eventplatform.promotions.domain.enums.PromotionStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record PromotionResponse(
    Long id,
    Long orgId,
    String name,
    DiscountType discountType,
    BigDecimal discountValue,
    PromotionScope scope,
    String ebEventId,
    Integer maxUsageLimit,
    Integer perUserCap,
    Instant validFrom,
    Instant validUntil,
    PromotionStatus status,
    Instant createdAt,
    Instant updatedAt
) {
}

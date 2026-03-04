package com.eventplatform.promotions.api.dto.response;

import com.eventplatform.promotions.domain.enums.CouponStatus;
import com.eventplatform.promotions.domain.enums.EbSyncStatus;
import java.time.Instant;

public record CouponResponse(
    Long id,
    Long promotionId,
    Long orgId,
    String code,
    CouponStatus status,
    Integer redemptionCount,
    EbSyncStatus ebSyncStatus,
    String ebDiscountId,
    Integer ebQuantitySoldAtLastSync,
    Instant lastEbSyncAt,
    Instant createdAt
) {
}

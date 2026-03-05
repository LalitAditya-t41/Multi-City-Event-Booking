package com.eventplatform.promotions.api.dto.response;

import com.eventplatform.promotions.domain.enums.CouponStatus;
import com.eventplatform.promotions.domain.enums.EbSyncStatus;
import java.time.Instant;

public record CouponUsageStatsResponse(
    String couponCode,
    CouponStatus status,
    int redemptionCount,
    long activeReservations,
    long voidedRedemptions,
    EbSyncStatus ebSyncStatus,
    Instant lastEbSyncAt,
    Integer ebQuantitySoldAtLastSync) {}

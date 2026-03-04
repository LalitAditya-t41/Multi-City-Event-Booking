package com.eventplatform.promotions.api.dto.request;

import java.time.Instant;

public record PromotionUpdateRequest(
    Instant validFrom,
    Instant validUntil,
    Integer maxUsageLimit,
    Integer perUserCap
) {
}

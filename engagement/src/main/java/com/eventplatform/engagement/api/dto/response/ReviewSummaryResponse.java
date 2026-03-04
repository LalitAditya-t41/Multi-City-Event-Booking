package com.eventplatform.engagement.api.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record ReviewSummaryResponse(
    Long eventId,
    BigDecimal averageRating,
    long totalReviews,
    Map<Integer, Long> distribution,
    Instant cachedAt
) {
}

package com.eventplatform.engagement.service.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record ReviewRatingSummary(
    Long eventId,
    BigDecimal averageRating,
    long totalReviews,
    Map<Integer, Long> distribution,
    Instant cachedAt
) {
}

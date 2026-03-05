package com.eventplatform.promotions.api.dto.response;

import java.time.Instant;

public record DiscountReconciliationLogResponse(
    Long orgId,
    Instant runAt,
    int discountsChecked,
    int driftsFound,
    int orphansFound,
    int externallyDeletedFound,
    String actionsTakenSummary,
    String errorSummary) {}

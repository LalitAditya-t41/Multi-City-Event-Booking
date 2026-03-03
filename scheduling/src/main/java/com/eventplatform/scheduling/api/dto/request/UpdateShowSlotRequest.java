package com.eventplatform.scheduling.api.dto.request;

import jakarta.validation.Valid;
import java.time.ZonedDateTime;
import java.util.List;

public record UpdateShowSlotRequest(
    String title,
    String description,
    ZonedDateTime startTime,
    ZonedDateTime endTime,
    Integer capacity,
    List<@Valid PricingTierRequest> pricingTiers
) {
}

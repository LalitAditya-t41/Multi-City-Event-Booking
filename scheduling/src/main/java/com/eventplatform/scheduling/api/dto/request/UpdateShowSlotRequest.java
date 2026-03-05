package com.eventplatform.scheduling.api.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import java.time.ZonedDateTime;
import java.util.List;

public record UpdateShowSlotRequest(
    String title,
    String description,
    ZonedDateTime startTime,
    ZonedDateTime endTime,
    Integer capacity,
    List<@Valid PricingTierRequest> pricingTiers) {
  @AssertTrue(message = "endTime must be after startTime")
  public boolean isValidTimeRange() {
    if (startTime == null || endTime == null) {
      return true;
    }
    return endTime.isAfter(startTime);
  }
}

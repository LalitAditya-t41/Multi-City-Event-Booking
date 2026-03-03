package com.eventplatform.scheduling.api.dto.request;

import com.eventplatform.shared.common.enums.SeatingMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.ZonedDateTime;
import java.util.List;

public record CreateShowSlotRequest(
    @NotNull Long venueId,
    @NotBlank String title,
    String description,
    @NotNull ZonedDateTime startTime,
    @NotNull ZonedDateTime endTime,
    @NotNull SeatingMode seatingMode,
    @NotNull @Positive Integer capacity,
    @NotEmpty List<@Valid PricingTierRequest> pricingTiers,
    @NotNull Boolean isRecurring,
    String recurrenceRule,
    String sourceSeatMapId
) {
    @AssertTrue(message = "endTime must be after startTime")
    public boolean isValidTimeRange() {
        return startTime == null || endTime == null || endTime.isAfter(startTime);
    }
}

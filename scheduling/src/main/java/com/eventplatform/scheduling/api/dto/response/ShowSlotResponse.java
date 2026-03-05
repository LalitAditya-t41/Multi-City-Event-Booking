package com.eventplatform.scheduling.api.dto.response;

import com.eventplatform.scheduling.domain.enums.ShowSlotStatus;
import com.eventplatform.shared.common.enums.SeatingMode;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;

public record ShowSlotResponse(
    Long id,
    Long venueId,
    Long cityId,
    String title,
    String description,
    ZonedDateTime startTime,
    ZonedDateTime endTime,
    SeatingMode seatingMode,
    Integer capacity,
    ShowSlotStatus status,
    boolean isRecurring,
    String ebEventId,
    String ebSeriesId,
    int syncAttemptCount,
    String lastSyncError,
    Instant lastAttemptedAt,
    List<ShowSlotPricingTierResponse> pricingTiers,
    Instant createdAt,
    Instant updatedAt) {}

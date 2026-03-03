package com.eventplatform.shared.eventbrite.dto.request;

import java.time.ZonedDateTime;

public record EbEventCreateRequest(
    String title,
    String description,
    String venueId,
    ZonedDateTime startTime,
    ZonedDateTime endTime,
    Integer capacity,
    boolean isSeries,
    String currency
) {
}

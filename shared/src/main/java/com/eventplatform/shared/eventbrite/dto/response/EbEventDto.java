package com.eventplatform.shared.eventbrite.dto.response;

import java.time.Instant;

public record EbEventDto(
    String id,
    String venueId,
    String name,
    String description,
    String url,
    Instant startTime,
    Instant endTime,
    String state,
    String currency,
    Instant changedAt
) {
}

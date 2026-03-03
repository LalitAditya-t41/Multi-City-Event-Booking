package com.eventplatform.shared.eventbrite.dto;

import java.time.ZonedDateTime;

public record EbEventUpdateRequest(
    String title,
    String description,
    ZonedDateTime startTime,
    ZonedDateTime endTime
) {
}

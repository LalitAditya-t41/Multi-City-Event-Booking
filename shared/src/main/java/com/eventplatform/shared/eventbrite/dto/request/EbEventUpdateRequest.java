package com.eventplatform.shared.eventbrite.dto.request;

import java.time.ZonedDateTime;

public record EbEventUpdateRequest(
    String title,
    String description,
    ZonedDateTime startTime,
    ZonedDateTime endTime
) {
}

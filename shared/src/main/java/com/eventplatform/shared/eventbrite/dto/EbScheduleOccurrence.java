package com.eventplatform.shared.eventbrite.dto;

import java.time.ZonedDateTime;

public record EbScheduleOccurrence(
    String eventId,
    ZonedDateTime startTime,
    ZonedDateTime endTime
) {
}

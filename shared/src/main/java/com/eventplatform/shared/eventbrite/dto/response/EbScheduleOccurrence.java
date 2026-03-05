package com.eventplatform.shared.eventbrite.dto.response;
@JsonIgnoreProperties(ignoreUnknown = true)
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.ZonedDateTime;

public record EbScheduleOccurrence(
    String eventId, ZonedDateTime startTime, ZonedDateTime endTime) {}

package com.eventplatform.shared.eventbrite.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.ZonedDateTime;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EbScheduleOccurrence(
    String eventId, ZonedDateTime startTime, ZonedDateTime endTime) {}

package com.eventplatform.shared.eventbrite.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents an Eventbrite date-time object with both a UTC timestamp and a timezone string.
 * Serializes to: {@code {"utc": "2025-06-01T18:00:00Z", "timezone": "Asia/Kolkata"}}
 */
public record EbTimeDto(
    @JsonProperty("utc")      String utc,
    @JsonProperty("timezone") String timezone
) {
}

package com.eventplatform.shared.eventbrite.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Eventbrite v3 event creation payload (the inner object).
 * The facade wraps this in {@code {"event": <this>}} before POSTing.
 *
 * <p>Serialises to:
 * <pre>
 * {
 *   "name":        {"html": "..."},
 *   "description": {"html": "..."},
 *   "start":       {"utc": "...", "timezone": "..."},
 *   "end":         {"utc": "...", "timezone": "..."},
 *   "venue_id":    "eb-venue-xxx",
 *   "capacity":    500,
 *   "is_series":   false,
 *   "currency":    "INR"
 * }
 * </pre>
 */
public record EbEventCreateRequest(
    @JsonProperty("name")        EbHtmlText name,
    @JsonProperty("description") EbHtmlText description,
    @JsonProperty("venue_id")    String venueId,
    @JsonProperty("start")       EbTimeDto start,
    @JsonProperty("end")         EbTimeDto end,
    @JsonProperty("capacity")    Integer capacity,
    @JsonProperty("is_series")   boolean isSeries,
    @JsonProperty("currency")    String currency
) {
    /**
     * Convenience factory — converts plain strings and {@link java.time.ZonedDateTime} values
     * into the nested objects Eventbrite expects.
     */
    public static EbEventCreateRequest of(
        String title,
        String description,
        String venueId,
        java.time.ZonedDateTime startTime,
        java.time.ZonedDateTime endTime,
        Integer capacity,
        boolean isSeries,
        String currency
    ) {
        String timezone = startTime.getZone().getId();
        return new EbEventCreateRequest(
            new EbHtmlText(title),
            description != null ? new EbHtmlText(description) : null,
            venueId,
            new EbTimeDto(startTime.toInstant().toString(), timezone),
            new EbTimeDto(endTime.toInstant().toString(), endTime.getZone().getId()),
            capacity,
            isSeries,
            currency
        );
    }
}

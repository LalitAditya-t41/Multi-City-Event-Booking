package com.eventplatform.shared.eventbrite.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Eventbrite v3 event update payload (the inner object).
 * The facade wraps this in {@code {"event": <this>}} before POSTing.
 */
public record EbEventUpdateRequest(
    @JsonProperty("name")        EbHtmlText name,
    @JsonProperty("description") EbHtmlText description,
    @JsonProperty("start")       EbTimeDto start,
    @JsonProperty("end")         EbTimeDto end
) {
    public static EbEventUpdateRequest of(
        String title,
        String description,
        java.time.ZonedDateTime startTime,
        java.time.ZonedDateTime endTime
    ) {
        return new EbEventUpdateRequest(
            title != null ? new EbHtmlText(title) : null,
            description != null ? new EbHtmlText(description) : null,
            startTime != null ? new EbTimeDto(startTime.toInstant().toString(), startTime.getZone().getId()) : null,
            endTime != null ? new EbTimeDto(endTime.toInstant().toString(), endTime.getZone().getId()) : null
        );
    }
}

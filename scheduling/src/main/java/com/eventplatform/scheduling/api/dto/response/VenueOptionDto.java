package com.eventplatform.scheduling.api.dto.response;

public record VenueOptionDto(
    Long venueId,
    String venueName,
    String city,
    int capacity
) {
}

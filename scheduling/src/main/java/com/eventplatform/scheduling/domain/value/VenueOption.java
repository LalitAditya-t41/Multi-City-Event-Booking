package com.eventplatform.scheduling.domain.value;

public record VenueOption(
    Long venueId,
    String venueName,
    String city,
    int capacity
) {
}

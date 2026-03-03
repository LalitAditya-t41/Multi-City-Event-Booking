package com.eventplatform.shared.eventbrite.dto.response;

public record EbCapacityResponse(
    String eventId,
    Integer capacity
) {
}

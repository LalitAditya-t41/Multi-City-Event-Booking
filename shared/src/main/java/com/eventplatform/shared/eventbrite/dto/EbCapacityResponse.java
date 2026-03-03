package com.eventplatform.shared.eventbrite.dto;

public record EbCapacityResponse(
    String eventId,
    Integer capacity
) {
}

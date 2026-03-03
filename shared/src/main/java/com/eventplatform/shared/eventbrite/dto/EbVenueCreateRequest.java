package com.eventplatform.shared.eventbrite.dto;

public record EbVenueCreateRequest(
    String name,
    String addressLine1,
    String addressLine2,
    String city,
    String country,
    String zipCode,
    String latitude,
    String longitude
) {
}

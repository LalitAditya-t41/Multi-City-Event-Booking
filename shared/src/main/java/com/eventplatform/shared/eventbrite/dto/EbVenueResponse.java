package com.eventplatform.shared.eventbrite.dto;

public record EbVenueResponse(
    String id,
    String name,
    String address,
    String city,
    String country,
    String zipCode,
    String latitude,
    String longitude
) {
}

package com.eventplatform.discoverycatalog.api.dto.response;

public record VenueResponse(
    Long id,
    Long cityId,
    String eventbriteVenueId,
    String name,
    String address,
    String zipCode,
    String latitude,
    String longitude
) {
}

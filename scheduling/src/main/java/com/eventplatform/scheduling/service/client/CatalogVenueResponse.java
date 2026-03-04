package com.eventplatform.scheduling.service.client;

import com.eventplatform.shared.common.enums.SeatingMode;

public record CatalogVenueResponse(
    Long id,
    Long cityId,
    String eventbriteVenueId,
    String name,
    String address,
    Integer capacity,
    SeatingMode seatingMode
) {
}

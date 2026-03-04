package com.eventplatform.bookinginventory.service.client;

import java.util.List;

public record CatalogSeatLayoutResponse(
    Long venueId,
    Integer totalSeats,
    List<CatalogSeatResponse> seats
) {
}

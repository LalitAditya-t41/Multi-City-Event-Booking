package com.eventplatform.discoverycatalog.api.dto.response;

import java.util.List;

public record VenueSeatLayoutResponse(
    Long venueId,
    int totalSeats,
    List<VenueSeatResponse> seats
) {
}

package com.eventplatform.discoverycatalog.api.dto.response;

public record VenueSeatResponse(
    Long id,
    String section,
    String rowLabel,
    String seatNumber,
    String tierName,
    boolean accessible
) {
}

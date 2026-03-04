package com.eventplatform.bookinginventory.api.dto.response;

public record SeatSuggestionResponse(
    Long seatId,
    String seatNumber,
    String section,
    Long tierId
) {
}

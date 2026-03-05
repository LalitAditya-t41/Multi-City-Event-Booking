package com.eventplatform.bookinginventory.api.dto.response;

import java.util.List;

public record SeatAlternativesResponse(
    Long unavailableSeatId,
    List<SeatSuggestionResponse> sameSection,
    List<SeatSuggestionResponse> adjacentSection) {}

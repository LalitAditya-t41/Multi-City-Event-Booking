package com.eventplatform.bookinginventory.service;

import com.eventplatform.bookinginventory.api.dto.response.SeatAlternativesResponse;
import com.eventplatform.bookinginventory.api.dto.response.SeatSuggestionResponse;
import com.eventplatform.bookinginventory.domain.Seat;
import com.eventplatform.bookinginventory.repository.SeatRepository;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ConflictResolutionService {

    private final SeatRepository seatRepository;

    public ConflictResolutionService(SeatRepository seatRepository) {
        this.seatRepository = seatRepository;
    }

    public SeatAlternativesResponse alternativesFor(Seat requested) {
        List<SeatSuggestionResponse> sameSection = seatRepository
            .findAlternativesSameSection(requested.getShowSlotId(), requested.getSection(), requested.getPricingTierId())
            .stream()
            .limit(5)
            .map(this::toSuggestion)
            .toList();

        List<SeatSuggestionResponse> adjacent = seatRepository
            .findAlternativesAdjacentSection(requested.getShowSlotId(), requested.getSection(), requested.getPricingTierId())
            .stream()
            .limit(3)
            .map(this::toSuggestion)
            .toList();

        return new SeatAlternativesResponse(requested.getId(), sameSection, adjacent);
    }

    private SeatSuggestionResponse toSuggestion(Seat seat) {
        return new SeatSuggestionResponse(seat.getId(), seat.getSeatNumber(), seat.getSection(), seat.getPricingTierId());
    }
}

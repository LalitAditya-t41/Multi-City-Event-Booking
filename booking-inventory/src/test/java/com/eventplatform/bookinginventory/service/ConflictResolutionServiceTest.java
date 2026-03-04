package com.eventplatform.bookinginventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.eventplatform.bookinginventory.api.dto.response.SeatAlternativesResponse;
import com.eventplatform.bookinginventory.domain.Seat;
import com.eventplatform.bookinginventory.repository.SeatRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ConflictResolutionServiceTest {

    @Mock
    private SeatRepository seatRepository;

    @InjectMocks
    private ConflictResolutionService conflictResolutionService;

    @Test
    void should_return_up_to_5_same_section_alternatives() {
        Seat requested = seat(1L, "A1", "S1");
        when(seatRepository.findAlternativesSameSection(100L, "S1", 10L)).thenReturn(List.of(
            seat(2L, "A2", "S1"), seat(3L, "A3", "S1"), seat(4L, "A4", "S1"),
            seat(5L, "A5", "S1"), seat(6L, "A6", "S1"), seat(7L, "A7", "S1")
        ));
        when(seatRepository.findAlternativesAdjacentSection(100L, "S1", 10L)).thenReturn(List.of());

        SeatAlternativesResponse response = conflictResolutionService.alternativesFor(requested);

        assertThat(response.sameSection()).hasSize(5);
    }

    @Test
    void should_return_up_to_3_adjacent_section_alternatives_when_same_section_insufficient() {
        Seat requested = seat(1L, "A1", "S1");
        when(seatRepository.findAlternativesSameSection(100L, "S1", 10L)).thenReturn(List.of(seat(2L, "A2", "S1")));
        when(seatRepository.findAlternativesAdjacentSection(100L, "S1", 10L)).thenReturn(List.of(
            seat(8L, "B1", "S2"), seat(9L, "B2", "S2"), seat(10L, "B3", "S3"), seat(11L, "B4", "S3")
        ));

        SeatAlternativesResponse response = conflictResolutionService.alternativesFor(requested);

        assertThat(response.adjacentSection()).hasSize(3);
    }

    private Seat seat(Long id, String seatNumber, String section) {
        Seat seat = new Seat(100L, 10L, "tc_10", seatNumber, "A", section);
        ReflectionTestUtils.setField(seat, "id", id);
        return seat;
    }
}

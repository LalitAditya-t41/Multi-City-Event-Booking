package com.eventplatform.discoverycatalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.eventplatform.discoverycatalog.api.dto.response.VenueSeatLayoutResponse;
import com.eventplatform.discoverycatalog.api.dto.response.VenueSeatResponse;
import com.eventplatform.discoverycatalog.domain.Venue;
import com.eventplatform.discoverycatalog.domain.VenueSeat;
import com.eventplatform.discoverycatalog.exception.CatalogNotFoundException;
import com.eventplatform.discoverycatalog.mapper.VenueMapper;
import com.eventplatform.discoverycatalog.repository.CityRepository;
import com.eventplatform.discoverycatalog.repository.VenueRepository;
import com.eventplatform.discoverycatalog.repository.VenueSeatRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VenueCatalogServiceTest {

    @Mock
    private VenueRepository venueRepository;
    @Mock
    private CityRepository cityRepository;
    @Mock
    private VenueMapper venueMapper;
    @Mock
    private VenueSeatRepository venueSeatRepository;

    @InjectMocks
    private VenueCatalogService venueCatalogService;

    @Test
    void should_return_seat_layout_with_correct_total_when_seats_exist() {
        Long venueId = 1L;
        VenueSeat seat1 = new VenueSeat(null, "A", "R1", "1", "GA", false);
        VenueSeat seat2 = new VenueSeat(null, "A", "R1", "2", "GA", false);
        VenueSeatResponse resp1 = new VenueSeatResponse(1L, "A", "R1", "1", "GA", false);
        VenueSeatResponse resp2 = new VenueSeatResponse(2L, "A", "R1", "2", "GA", false);

        when(venueRepository.existsById(venueId)).thenReturn(true);
        when(venueSeatRepository.findByVenueId(venueId)).thenReturn(List.of(seat1, seat2));
        when(venueMapper.toSeatResponse(seat1)).thenReturn(resp1);
        when(venueMapper.toSeatResponse(seat2)).thenReturn(resp2);

        VenueSeatLayoutResponse result = venueCatalogService.getVenueSeatLayout(venueId);

        assertThat(result.venueId()).isEqualTo(venueId);
        assertThat(result.totalSeats()).isEqualTo(2);
        assertThat(result.seats()).hasSize(2);
    }

    @Test
    void should_return_empty_seat_layout_when_no_seats() {
        Long venueId = 2L;
        when(venueRepository.existsById(venueId)).thenReturn(true);
        when(venueSeatRepository.findByVenueId(venueId)).thenReturn(List.of());

        VenueSeatLayoutResponse result = venueCatalogService.getVenueSeatLayout(venueId);

        assertThat(result.venueId()).isEqualTo(venueId);
        assertThat(result.totalSeats()).isEqualTo(0);
        assertThat(result.seats()).isEmpty();
    }

    @Test
    void should_throw_CatalogNotFoundException_when_venue_not_found() {
        when(venueRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> venueCatalogService.getVenueSeatLayout(99L))
            .isInstanceOf(CatalogNotFoundException.class)
            .hasMessageContaining("Venue not found");
    }
}

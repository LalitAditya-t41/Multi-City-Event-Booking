package com.eventplatform.discoverycatalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eventplatform.discoverycatalog.api.dto.request.CreateVenueRequest;
import com.eventplatform.discoverycatalog.api.dto.request.UpdateVenueRequest;
import com.eventplatform.discoverycatalog.domain.Venue;
import com.eventplatform.discoverycatalog.domain.enums.VenueSyncStatus;
import com.eventplatform.discoverycatalog.exception.CatalogNotFoundException;
import com.eventplatform.discoverycatalog.mapper.VenueMapper;
import com.eventplatform.discoverycatalog.repository.CityRepository;
import com.eventplatform.discoverycatalog.repository.VenueRepository;
import com.eventplatform.discoverycatalog.service.cache.EventCatalogSnapshotCache;
import com.eventplatform.shared.common.enums.SeatingMode;
import com.eventplatform.shared.eventbrite.dto.response.EbVenueResponse;
import com.eventplatform.shared.eventbrite.exception.EbIntegrationException;
import com.eventplatform.shared.eventbrite.service.EbVenueService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class VenueServiceTest {

    @Mock
    private VenueRepository venueRepository;
    @Mock
    private CityRepository cityRepository;
    @Mock
    private VenueMapper venueMapper;
    @Mock
    private EbVenueService ebVenueService;
    @Mock
    private EventCatalogSnapshotCache snapshotCache;

    @InjectMocks
    private VenueService venueService;

    @Test
    void should_persist_venue_and_set_SYNCED_when_EB_creation_succeeds() {
        CreateVenueRequest request = baseCreateRequest();
        Venue venue = baseVenue();
        when(cityRepository.existsById(request.cityId())).thenReturn(true);
        when(venueRepository.findByOrganizationIdAndCityId(eq(1L), eq(request.cityId()), any(PageRequest.class)))
            .thenReturn(new PageImpl<>(List.of()));
        when(venueMapper.toEntity(1L, request)).thenReturn(venue);
        when(venueRepository.save(venue)).thenReturn(venue);
        when(ebVenueService.createVenue(eq(1L), any()))
            .thenReturn(new EbVenueResponse("eb-1", "Venue", "Addr", "City", "IN", "123", null, null));

        Venue result = venueService.createVenue(1L, request);

        assertThat(result.getSyncStatus()).isEqualTo(VenueSyncStatus.SYNCED);
        assertThat(result.getEventbriteVenueId()).isEqualTo("eb-1");
    }

    @Test
    void should_persist_venue_and_return_202_when_EB_create_fails() {
        CreateVenueRequest request = baseCreateRequest();
        Venue venue = baseVenue();
        when(cityRepository.existsById(request.cityId())).thenReturn(true);
        when(venueRepository.findByOrganizationIdAndCityId(eq(1L), eq(request.cityId()), any(PageRequest.class)))
            .thenReturn(new PageImpl<>(List.of()));
        when(venueMapper.toEntity(1L, request)).thenReturn(venue);
        when(venueRepository.save(venue)).thenReturn(venue);
        when(ebVenueService.createVenue(eq(1L), any()))
            .thenThrow(new EbIntegrationException("boom"));

        Venue result = venueService.createVenue(1L, request);

        assertThat(result.getSyncStatus()).isEqualTo(VenueSyncStatus.PENDING_SYNC);
        assertThat(result.getLastSyncError()).isNotNull();
    }

    @Test
    void should_update_venue_and_call_updateVenue_when_eb_venue_id_present() {
        UpdateVenueRequest request = baseUpdateRequest();
        Venue venue = baseVenue();
        venue.markSynced("eb-1");
        when(venueRepository.findById(10L)).thenReturn(Optional.of(venue));
        when(venueRepository.save(venue)).thenReturn(venue);
        when(ebVenueService.updateVenue(eq(1L), eq("eb-1"), any()))
            .thenReturn(new EbVenueResponse("eb-1", "Venue", "Addr", "City", "IN", "123", null, null));

        Venue result = venueService.updateVenue(1L, 10L, request);

        assertThat(result.getSyncStatus()).isEqualTo(VenueSyncStatus.SYNCED);
        verify(snapshotCache).invalidate(1L, venue.getCityId());
    }

    @Test
    void should_set_DRIFT_FLAGGED_when_updateVenue_fails() {
        UpdateVenueRequest request = baseUpdateRequest();
        Venue venue = baseVenue();
        venue.markSynced("eb-1");
        when(venueRepository.findById(10L)).thenReturn(Optional.of(venue));
        when(venueRepository.save(venue)).thenReturn(venue);
        when(ebVenueService.updateVenue(eq(1L), eq("eb-1"), any()))
            .thenThrow(new EbIntegrationException("boom"));

        Venue result = venueService.updateVenue(1L, 10L, request);

        assertThat(result.getSyncStatus()).isEqualTo(VenueSyncStatus.DRIFT_FLAGGED);
        verify(snapshotCache).invalidate(1L, venue.getCityId());
    }

    @Test
    void should_throw_CatalogNotFoundException_when_venue_not_found_by_id() {
        when(venueRepository.findById(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> venueService.updateVenue(1L, 10L, baseUpdateRequest()))
            .isInstanceOf(CatalogNotFoundException.class);
    }

    private CreateVenueRequest baseCreateRequest() {
        return new CreateVenueRequest(
            "Venue",
            "Addr1",
            "Addr2",
            3L,
            "IN",
            "123",
            "12.9",
            "77.6",
            100,
            SeatingMode.GA
        );
    }

    private UpdateVenueRequest baseUpdateRequest() {
        return new UpdateVenueRequest(
            "Venue",
            "Addr1",
            "Addr2",
            3L,
            "IN",
            "123",
            "12.9",
            "77.6",
            120,
            SeatingMode.GA
        );
    }

    private Venue baseVenue() {
        return new Venue(1L, 3L, "Venue", "Addr", "123", "12.9", "77.6", 100, SeatingMode.GA);
    }
}

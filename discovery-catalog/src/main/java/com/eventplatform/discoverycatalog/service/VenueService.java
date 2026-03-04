package com.eventplatform.discoverycatalog.service;

import com.eventplatform.discoverycatalog.api.dto.request.CreateVenueRequest;
import com.eventplatform.discoverycatalog.api.dto.request.UpdateVenueRequest;
import com.eventplatform.discoverycatalog.domain.Venue;
import com.eventplatform.discoverycatalog.domain.enums.VenueSyncStatus;
import com.eventplatform.discoverycatalog.exception.CatalogNotFoundException;
import com.eventplatform.discoverycatalog.exception.CatalogSyncException;
import com.eventplatform.discoverycatalog.exception.InvalidCatalogSearchException;
import com.eventplatform.discoverycatalog.mapper.VenueMapper;
import com.eventplatform.discoverycatalog.repository.CityRepository;
import com.eventplatform.discoverycatalog.repository.VenueRepository;
import com.eventplatform.discoverycatalog.service.cache.EventCatalogSnapshotCache;
import com.eventplatform.shared.eventbrite.dto.request.EbVenueCreateRequest;
import com.eventplatform.shared.eventbrite.dto.response.EbVenueResponse;
import com.eventplatform.shared.eventbrite.exception.EbIntegrationException;
import com.eventplatform.shared.eventbrite.service.EbVenueService;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VenueService {

    private final VenueRepository venueRepository;
    private final CityRepository cityRepository;
    private final VenueMapper venueMapper;
    private final EbVenueService ebVenueService;
    private final EventCatalogSnapshotCache snapshotCache;

    public VenueService(
        VenueRepository venueRepository,
        CityRepository cityRepository,
        VenueMapper venueMapper,
        EbVenueService ebVenueService,
        EventCatalogSnapshotCache snapshotCache
    ) {
        this.venueRepository = venueRepository;
        this.cityRepository = cityRepository;
        this.venueMapper = venueMapper;
        this.ebVenueService = ebVenueService;
        this.snapshotCache = snapshotCache;
    }

    @Transactional
    public Venue createVenue(Long organizationId, CreateVenueRequest request) {
        if (!cityRepository.existsById(request.cityId())) {
            throw new CatalogNotFoundException("City not found: " + request.cityId());
        }
        boolean nameExists = venueRepository
            .findByOrganizationIdAndCityId(organizationId, request.cityId(), PageRequest.of(0, 1))
            .stream()
            .anyMatch(venue -> venue.getName().equalsIgnoreCase(request.name()));
        if (nameExists) {
            throw new InvalidCatalogSearchException("Venue already exists in city: " + request.name());
        }

        Venue venue = venueMapper.toEntity(organizationId, request);
        venue = venueRepository.save(venue);

        try {
            EbVenueResponse response = ebVenueService.createVenue(organizationId, toEbRequest(request));
            venue.markSynced(response.id());
            return venueRepository.save(venue);
        } catch (EbIntegrationException ex) {
            venue.markSyncFailed(ex.getMessage());
            venueRepository.save(venue);
            return venue;
        } catch (Exception ex) {
            venue.markSyncFailed(ex.getMessage());
            venueRepository.save(venue);
            return venue;
        }
    }

    @Transactional
    public Venue updateVenue(Long organizationId, Long venueId, UpdateVenueRequest request) {
        Venue venue = venueRepository.findById(venueId)
            .orElseThrow(() -> new CatalogNotFoundException("Venue not found: " + venueId));

        String formattedAddress = venueMapper.formatAddress(request.addressLine1(), request.addressLine2(), request.country());
        venue.updateDetails(
            request.name(),
            formattedAddress,
            request.zipCode(),
            request.latitude(),
            request.longitude(),
            request.capacity(),
            request.seatingMode()
        );
        venue = venueRepository.save(venue);

        try {
            if (venue.getEventbriteVenueId() != null) {
                ebVenueService.updateVenue(organizationId, venue.getEventbriteVenueId(), toEbRequest(request));
                venue.markSynced(venue.getEventbriteVenueId());
            } else {
                EbVenueResponse response = ebVenueService.createVenue(organizationId, toEbRequest(request));
                venue.markSynced(response.id());
            }
            venue = venueRepository.save(venue);
        } catch (EbIntegrationException ex) {
            venue.markDrift(ex.getMessage());
            venueRepository.save(venue);
        } finally {
            snapshotCache.invalidate(organizationId, venue.getCityId());
        }

        return venue;
    }

    @Transactional
    public Venue syncVenue(Long organizationId, Long venueId) {
        Venue venue = venueRepository.findById(venueId)
            .orElseThrow(() -> new CatalogNotFoundException("Venue not found: " + venueId));

        try {
            EbVenueResponse response;
            if (venue.getEventbriteVenueId() == null) {
                response = ebVenueService.createVenue(organizationId, toEbRequest(venue));
                venue.markSynced(response.id());
            } else {
                response = ebVenueService.updateVenue(organizationId, venue.getEventbriteVenueId(), toEbRequest(venue));
                venue.markSynced(Optional.ofNullable(response.id()).orElse(venue.getEventbriteVenueId()));
            }
            venue = venueRepository.save(venue);
            return venue;
        } catch (EbIntegrationException ex) {
            venue.markSyncFailed(ex.getMessage());
            venueRepository.save(venue);
            return venue;
        } finally {
            snapshotCache.invalidate(organizationId, venue.getCityId());
        }
    }

    @Transactional(readOnly = true)
    public Page<Venue> listFlaggedVenues(int page, int size) {
        return venueRepository.findBySyncStatus(VenueSyncStatus.DRIFT_FLAGGED, PageRequest.of(page, size));
    }

    private EbVenueCreateRequest toEbRequest(CreateVenueRequest request) {
        return new EbVenueCreateRequest(
            request.name(),
            request.addressLine1(),
            request.addressLine2(),
            null,
            request.country(),
            request.zipCode(),
            request.latitude(),
            request.longitude()
        );
    }

    private EbVenueCreateRequest toEbRequest(UpdateVenueRequest request) {
        return new EbVenueCreateRequest(
            request.name(),
            request.addressLine1(),
            request.addressLine2(),
            null,
            request.country(),
            request.zipCode(),
            request.latitude(),
            request.longitude()
        );
    }

    private EbVenueCreateRequest toEbRequest(Venue venue) {
        return new EbVenueCreateRequest(
            venue.getName(),
            venue.getAddress(),
            null,
            null,
            null,
            venue.getZipCode(),
            venue.getLatitude(),
            venue.getLongitude()
        );
    }
}

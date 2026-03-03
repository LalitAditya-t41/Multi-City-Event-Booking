package com.eventplatform.discoverycatalog.service;

import com.eventplatform.discoverycatalog.api.dto.response.PaginationInfo;
import com.eventplatform.discoverycatalog.api.dto.response.VenueListResponse;
import com.eventplatform.discoverycatalog.api.dto.response.VenueResponse;
import com.eventplatform.discoverycatalog.exception.CatalogNotFoundException;
import com.eventplatform.discoverycatalog.exception.InvalidCatalogSearchException;
import com.eventplatform.discoverycatalog.mapper.VenueMapper;
import com.eventplatform.discoverycatalog.repository.CityRepository;
import com.eventplatform.discoverycatalog.repository.VenueRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class VenueCatalogService {

    private final VenueRepository venueRepository;
    private final CityRepository cityRepository;
    private final VenueMapper venueMapper;

    public VenueCatalogService(
        VenueRepository venueRepository,
        CityRepository cityRepository,
        VenueMapper venueMapper
    ) {
        this.venueRepository = venueRepository;
        this.cityRepository = cityRepository;
        this.venueMapper = venueMapper;
    }

    public VenueListResponse listVenues(Long organizationId, Long cityId, int page, int size) {
        if (cityId == null) {
            throw new InvalidCatalogSearchException("cityId is required");
        }
        boolean cityExists = cityRepository.existsById(cityId);
        if (!cityExists) {
            throw new CatalogNotFoundException("City not found: " + cityId);
        }
        Page<VenueResponse> venues = venueRepository.findByOrganizationIdAndCityId(
            organizationId,
            cityId,
            PageRequest.of(page, size)
        ).map(venueMapper::toResponse);
        PaginationInfo pagination = new PaginationInfo(page, size, venues.getTotalElements(), venues.getTotalPages());
        return new VenueListResponse(venues.getContent(), pagination);
    }

    public VenueResponse getVenue(Long venueId) {
        return venueRepository.findById(venueId)
            .map(venueMapper::toResponse)
            .orElseThrow(() -> new CatalogNotFoundException("Venue not found: " + venueId));
    }
}

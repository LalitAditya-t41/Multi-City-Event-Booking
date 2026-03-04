package com.eventplatform.discoverycatalog.service;

import com.eventplatform.discoverycatalog.api.dto.request.CreateVenueRequest;
import com.eventplatform.discoverycatalog.api.dto.request.UpdateVenueRequest;
import com.eventplatform.discoverycatalog.domain.Venue;
import org.springframework.data.domain.Page;

/**
 * Interface for VenueService to enable JDK proxy-based mocking in tests,
 * avoiding ByteBuddy class instrumentation issues with Eventbrite ACL types.
 */
public interface VenueServiceI {

    Venue createVenue(Long organizationId, CreateVenueRequest request);

    Venue updateVenue(Long organizationId, Long venueId, UpdateVenueRequest request);

    Venue syncVenue(Long organizationId, Long venueId);

    Page<Venue> listFlaggedVenues(int page, int size);
}

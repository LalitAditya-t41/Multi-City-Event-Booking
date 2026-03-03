package com.eventplatform.shared.eventbrite.service;

import com.eventplatform.shared.eventbrite.dto.request.EbVenueCreateRequest;
import com.eventplatform.shared.eventbrite.dto.response.EbVenueResponse;
import com.eventplatform.shared.eventbrite.exception.EbIntegrationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DefaultEbVenueService implements EbVenueService {

    private static final Logger log = LoggerFactory.getLogger(DefaultEbVenueService.class);

    @Override
    public EbVenueResponse createVenue(Long organizationId, EbVenueCreateRequest request) {
        log.warn("EbVenueService not configured. organizationId={}", organizationId);
        throw new EbIntegrationException("Eventbrite integration not configured");
    }

    @Override
    public EbVenueResponse updateVenue(Long organizationId, String ebVenueId, EbVenueCreateRequest request) {
        log.warn("EbVenueService not configured. organizationId={} ebVenueId={}", organizationId, ebVenueId);
        throw new EbIntegrationException("Eventbrite integration not configured");
    }

    @Override
    public EbVenueResponse getVenue(Long organizationId, String ebVenueId) {
        log.warn("EbVenueService not configured. organizationId={} ebVenueId={}", organizationId, ebVenueId);
        throw new EbIntegrationException("Eventbrite integration not configured");
    }
}

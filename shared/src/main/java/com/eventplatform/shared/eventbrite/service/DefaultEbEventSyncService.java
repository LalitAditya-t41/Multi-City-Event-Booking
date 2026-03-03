package com.eventplatform.shared.eventbrite.service;

import com.eventplatform.shared.eventbrite.dto.EbEventDto;
import com.eventplatform.shared.eventbrite.exception.EbIntegrationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DefaultEbEventSyncService implements EbEventSyncService {

    private static final Logger log = LoggerFactory.getLogger(DefaultEbEventSyncService.class);

    @Override
    public List<EbEventDto> listEventsByOrganization(String organizationId) {
        log.warn("EbEventSyncService not configured. organizationId={}", organizationId);
        throw new EbIntegrationException("Eventbrite integration not configured");
    }

    @Override
    public List<EbEventDto> listEventsByVenue(String venueId) {
        log.warn("EbEventSyncService not configured. venueId={}", venueId);
        throw new EbIntegrationException("Eventbrite integration not configured");
    }

    @Override
    public EbEventDto getEventById(String eventId) {
        log.warn("EbEventSyncService not configured. eventId={}", eventId);
        throw new EbIntegrationException("Eventbrite integration not configured");
    }
}

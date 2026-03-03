package com.eventplatform.shared.eventbrite.service;

import com.eventplatform.shared.eventbrite.dto.EbEventDto;
import java.util.List;

public interface EbEventSyncService {
    List<EbEventDto> listEventsByOrganization(String organizationId);

    List<EbEventDto> listEventsByVenue(String venueId);

    EbEventDto getEventById(String eventId);
}

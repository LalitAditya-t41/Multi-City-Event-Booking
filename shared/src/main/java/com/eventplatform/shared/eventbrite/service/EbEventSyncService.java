package com.eventplatform.shared.eventbrite.service;

import com.eventplatform.shared.eventbrite.dto.EbEventCreateRequest;
import com.eventplatform.shared.eventbrite.dto.EbEventDto;
import com.eventplatform.shared.eventbrite.dto.EbEventUpdateRequest;
import java.util.List;

public interface EbEventSyncService {
    List<EbEventDto> listEventsByOrganization(String organizationId);

    List<EbEventDto> listEventsByVenue(String venueId);

    EbEventDto getEventById(String eventId);

    EbEventDto createDraft(Long organizationId, EbEventCreateRequest request);

    EbEventDto updateEvent(Long organizationId, String eventId, EbEventUpdateRequest request);

    void publishEvent(Long organizationId, String eventId);

    void cancelEvent(Long organizationId, String eventId);
}

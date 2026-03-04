package com.eventplatform.discoverycatalog.service.internal;

import com.eventplatform.discoverycatalog.domain.EventCatalogItem;
import com.eventplatform.discoverycatalog.repository.EventCatalogRepository;
import com.eventplatform.shared.common.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InternalCatalogQueryService {

    private final EventCatalogRepository eventCatalogRepository;

    public InternalCatalogQueryService(EventCatalogRepository eventCatalogRepository) {
        this.eventCatalogRepository = eventCatalogRepository;
    }

    @Transactional(readOnly = true)
    public EventEbMetadataResponse getEventEbMetadata(Long eventId) {
        EventCatalogItem item = eventCatalogRepository.findById(eventId)
            .orElseThrow(() -> new ResourceNotFoundException("Event not found: " + eventId, "EVENT_NOT_FOUND"));
        return new EventEbMetadataResponse(item.getId(), item.getOrganizationId(), item.getEventbriteEventId());
    }

    public record EventEbMetadataResponse(Long eventId, Long orgId, String ebEventId) {
    }
}

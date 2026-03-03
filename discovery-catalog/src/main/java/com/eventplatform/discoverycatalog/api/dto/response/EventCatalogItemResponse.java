package com.eventplatform.discoverycatalog.api.dto.response;

import com.eventplatform.discoverycatalog.domain.enums.EventSource;
import com.eventplatform.discoverycatalog.domain.enums.EventState;
import java.time.Instant;

public record EventCatalogItemResponse(
    Long id,
    Long cityId,
    Long venueId,
    String eventbriteEventId,
    String name,
    String description,
    String url,
    Instant startTime,
    Instant endTime,
    EventState state,
    EventSource source,
    String currency
) {
}

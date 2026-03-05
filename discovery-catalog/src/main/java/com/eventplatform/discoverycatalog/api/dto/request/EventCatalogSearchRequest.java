package com.eventplatform.discoverycatalog.api.dto.request;

import com.eventplatform.discoverycatalog.domain.enums.EventState;
import java.time.Instant;

public record EventCatalogSearchRequest(
    Long cityId,
    Long venueId,
    String q,
    EventState state,
    Instant startAfter,
    Instant startBefore,
    int page,
    int size) {}

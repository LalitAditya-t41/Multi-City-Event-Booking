package com.eventplatform.discoverycatalog.api.dto.response;

import com.eventplatform.discoverycatalog.domain.enums.CatalogSource;
import java.time.Instant;
import java.util.List;

public record EventCatalogSearchResponse(
    List<EventCatalogItemResponse> events,
    boolean stale,
    Instant snapshotTimestamp,
    CatalogSource source,
    PaginationInfo pagination) {}

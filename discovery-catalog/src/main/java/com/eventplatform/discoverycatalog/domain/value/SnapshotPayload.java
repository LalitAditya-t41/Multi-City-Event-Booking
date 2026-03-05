package com.eventplatform.discoverycatalog.domain.value;

import com.eventplatform.discoverycatalog.api.dto.response.EventCatalogItemResponse;
import java.time.Instant;
import java.util.List;

public record SnapshotPayload(List<EventCatalogItemResponse> events, Instant snapshotTimestamp) {}

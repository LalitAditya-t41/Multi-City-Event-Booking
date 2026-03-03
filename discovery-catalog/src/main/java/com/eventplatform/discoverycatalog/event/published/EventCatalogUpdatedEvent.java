package com.eventplatform.discoverycatalog.event.published;

import java.time.Instant;

public record EventCatalogUpdatedEvent(Long organizationId, Long cityId, Long venueId, Instant updatedAt) {
}

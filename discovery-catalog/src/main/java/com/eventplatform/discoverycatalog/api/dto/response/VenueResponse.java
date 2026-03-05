package com.eventplatform.discoverycatalog.api.dto.response;

import com.eventplatform.discoverycatalog.domain.enums.VenueSyncStatus;
import com.eventplatform.shared.common.enums.SeatingMode;
import java.time.Instant;

public record VenueResponse(
    Long id,
    Long cityId,
    String eventbriteVenueId,
    String name,
    String address,
    String zipCode,
    String latitude,
    String longitude,
    Integer capacity,
    SeatingMode seatingMode,
    VenueSyncStatus syncStatus,
    String lastSyncError,
    Instant lastAttemptedAt,
    Instant createdAt) {}

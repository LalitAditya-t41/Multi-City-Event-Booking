package com.eventplatform.admin.service.client;

import java.time.Instant;

public record AdminVenueResponse(
    Long id,
    String name,
    Long cityId,
    String syncStatus,
    String lastSyncError,
    Instant lastAttemptedAt) {}

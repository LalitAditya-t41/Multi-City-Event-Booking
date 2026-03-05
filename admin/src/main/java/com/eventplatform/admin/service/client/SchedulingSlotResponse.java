package com.eventplatform.admin.service.client;

import java.time.Instant;

public record SchedulingSlotResponse(
    Long id,
    String title,
    String status,
    int syncAttemptCount,
    String lastSyncError,
    Instant lastAttemptedAt,
    String ebEventId) {}

package com.eventplatform.scheduling.api.dto.response;

import com.eventplatform.scheduling.domain.enums.ShowSlotStatus;
import java.time.ZonedDateTime;

public record ShowSlotOccurrenceResponse(
    Long id,
    Integer occurrenceIndex,
    ZonedDateTime startTime,
    ZonedDateTime endTime,
    ShowSlotStatus status,
    String ebEventId,
    int syncAttemptCount) {}

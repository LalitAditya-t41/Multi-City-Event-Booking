package com.eventplatform.scheduling.api.dto.response;

import com.eventplatform.scheduling.domain.enums.ShowSlotStatus;

public record ShowSlotSubmitResponse(
    Long id,
    ShowSlotStatus status,
    String ebEventId,
    int syncAttemptCount,
    String message
) {
}

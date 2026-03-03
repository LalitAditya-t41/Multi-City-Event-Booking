package com.eventplatform.scheduling.api.dto.response;

import com.eventplatform.scheduling.domain.enums.ShowSlotStatus;

public record ShowSlotRetryResponse(
    ShowSlotStatus status,
    String message
) {
}

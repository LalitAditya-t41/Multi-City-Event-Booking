package com.eventplatform.scheduling.api.dto.response;

import com.eventplatform.scheduling.domain.enums.ShowSlotStatus;

public record ShowSlotCancelResponse(
    Long id,
    ShowSlotStatus status,
    boolean ebCancelSynced,
    String message
) {
}

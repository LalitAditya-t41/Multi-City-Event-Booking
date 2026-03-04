package com.eventplatform.admin.service.client;

public record SchedulingSlotCancelResponse(
    Long slotId,
    String status,
    Boolean ebCancelled,
    String message
) {
}

package com.eventplatform.shared.common.event.published;

public record ShowSlotCancelledEvent(
    Long slotId,
    String ebEventId,
    Long orgId,
    Long venueId,
    Long cityId
) {
}

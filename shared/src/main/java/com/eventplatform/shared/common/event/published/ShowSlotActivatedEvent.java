package com.eventplatform.shared.common.event.published;

public record ShowSlotActivatedEvent(
    Long slotId,
    String ebEventId,
    Long orgId,
    Long venueId,
    Long cityId
) {
}

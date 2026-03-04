package com.eventplatform.shared.common.event.published;

public record TicketSyncCompletedEvent(
    Long slotId,
    String ebEventId
) {
}

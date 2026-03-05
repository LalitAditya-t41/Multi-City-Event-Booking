package com.eventplatform.shared.common.event.published;

public record TicketSyncFailedEvent(Long slotId, String ebEventId, String reason) {}

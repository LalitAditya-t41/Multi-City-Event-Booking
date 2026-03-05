package com.eventplatform.shared.common.event.published;

public record SlotSyncFailedEvent(Long slotId, String reason) {}

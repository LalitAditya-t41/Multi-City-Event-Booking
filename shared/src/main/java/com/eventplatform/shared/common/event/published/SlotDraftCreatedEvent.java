package com.eventplatform.shared.common.event.published;

import com.eventplatform.shared.common.enums.SeatingMode;
import java.util.List;

public record SlotDraftCreatedEvent(
    Long slotId,
    String ebEventId,
    List<Long> pricingTierIds,
    SeatingMode seatingMode,
    Long orgId,
    String sourceSeatMapId) {}

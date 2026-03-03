package com.eventplatform.bookinginventory.service.client;

import com.eventplatform.shared.common.enums.SeatingMode;
import java.util.List;

public record SchedulingSlotResponse(
    Long id,
    String ebEventId,
    SeatingMode seatingMode,
    String sourceSeatMapId,
    List<SchedulingPricingTierResponse> pricingTiers
) {
}

package com.eventplatform.bookinginventory.api.dto.response;

import com.eventplatform.shared.common.enums.SeatingMode;
import java.util.List;

public record AvailableSeatsEnvelopeResponse(
    Long slotId,
    SeatingMode seatingMode,
    List<AvailableSeatResponse> seats,
    List<GaTierAvailabilityResponse> tiers
) {
}

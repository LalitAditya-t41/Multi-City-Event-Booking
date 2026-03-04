package com.eventplatform.bookinginventory.api.dto.response;

import com.eventplatform.bookinginventory.domain.enums.SeatLockState;
import com.eventplatform.shared.common.domain.Money;

public record AvailableSeatResponse(
    Long seatId,
    String seatNumber,
    String rowLabel,
    String section,
    Long tierId,
    String tierName,
    Money basePrice,
    SeatLockState lockState
) {
}

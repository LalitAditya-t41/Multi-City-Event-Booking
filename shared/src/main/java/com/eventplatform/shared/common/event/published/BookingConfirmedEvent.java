package com.eventplatform.shared.common.event.published;

import java.util.List;

public record BookingConfirmedEvent(
    Long cartId,
    List<Long> seatIds,
    String ebOrderId,
    Long userId
) {
}

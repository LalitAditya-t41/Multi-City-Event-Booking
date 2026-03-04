package com.eventplatform.shared.common.event.published;

import java.util.List;

public record PaymentFailedEvent(
    Long cartId,
    List<Long> seatIds,
    Long userId
) {
}

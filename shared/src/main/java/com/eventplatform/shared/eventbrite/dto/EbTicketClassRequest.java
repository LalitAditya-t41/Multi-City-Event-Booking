package com.eventplatform.shared.eventbrite.dto;

import com.eventplatform.shared.common.domain.Money;

public record EbTicketClassRequest(
    String name,
    Money price,
    Integer quantity,
    String tierType
) {
}

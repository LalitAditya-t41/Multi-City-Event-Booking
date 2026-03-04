package com.eventplatform.bookinginventory.service.client;

import java.math.BigDecimal;

public record SchedulingPricingTierResponse(
    Long id,
    String name,
    BigDecimal priceAmount,
    String currency,
    Integer quota,
    String tierType,
    String ebTicketClassId
) {
}

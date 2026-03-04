package com.eventplatform.shared.common.dto;

import com.eventplatform.shared.common.domain.Money;
import java.math.BigDecimal;

public record PricingTierDto(
    Long tierId,
    String tierName,
    Money price,
    Integer quota,
    String tierType,
    String ebTicketClassId,
    String ebInventoryTierId,
    Integer groupDiscountThreshold,
    BigDecimal groupDiscountPercent
) {
}

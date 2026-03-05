package com.eventplatform.scheduling.api.dto.response;

import com.eventplatform.scheduling.domain.enums.TierType;
import java.math.BigDecimal;

public record ShowSlotPricingTierResponse(
    Long id,
    String name,
    BigDecimal priceAmount,
    String currency,
    Integer quota,
    TierType tierType,
    String ebTicketClassId,
    String ebInventoryTierId, // already in entity, was missing from DTO
    Integer groupDiscountThreshold, // nullable
    BigDecimal groupDiscountPercent // nullable
    ) {}

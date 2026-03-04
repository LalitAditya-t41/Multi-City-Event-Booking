package com.eventplatform.bookinginventory.api.dto.response;

import com.eventplatform.shared.common.domain.Money;

public record GaTierAvailabilityResponse(
    Long tierId,
    String tierName,
    Integer quota,
    Long available,
    Money basePrice,
    boolean blocked
) {
}

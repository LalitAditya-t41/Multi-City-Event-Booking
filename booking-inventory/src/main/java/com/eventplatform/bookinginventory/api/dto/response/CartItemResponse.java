package com.eventplatform.bookinginventory.api.dto.response;

import com.eventplatform.shared.common.domain.Money;

public record CartItemResponse(
    Long itemId,
    Long seatId,
    String seatNumber,
    Long tierId,
    String tierName,
    Integer quantity,
    Money unitPrice,
    Money discountApplied) {}

package com.eventplatform.promotions.api.dto.response;

import com.eventplatform.promotions.domain.enums.DiscountType;

public record DiscountBreakdownResponse(
    String couponCode,
    DiscountType discountType,
    long discountAmountInSmallestUnit,
    long adjustedCartTotalInSmallestUnit,
    String currency
) {
}

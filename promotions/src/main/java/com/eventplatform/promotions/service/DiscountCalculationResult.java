package com.eventplatform.promotions.service;

import com.eventplatform.promotions.domain.enums.DiscountType;

public record DiscountCalculationResult(
    String couponCode,
    DiscountType discountType,
    long discountAmountInSmallestUnit,
    long adjustedTotalInSmallestUnit,
    String currency
) {
}

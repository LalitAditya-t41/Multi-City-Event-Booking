package com.eventplatform.paymentsticketing.service;

public record RefundCalculationResult(
    int refundPercent,
    long refundAmountInSmallestUnit,
    String tierLabel
) {
}

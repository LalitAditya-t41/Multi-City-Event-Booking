package com.eventplatform.paymentsticketing.api.dto.response;

public record CancellationQuoteResponse(
    String bookingRef,
    String policyTier,
    Integer refundPercent,
    Long requestedItemsAmount,
    Long refundAmount,
    String currency
) {
}

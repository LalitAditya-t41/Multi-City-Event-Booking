package com.eventplatform.paymentsticketing.api.dto.response;

import com.eventplatform.paymentsticketing.domain.enums.BookingStatus;
import java.time.Instant;
import java.util.List;

public record BookingResponse(
    String bookingRef,
    BookingStatus status,
    Long slotId,
    Long totalAmount,
    String currency,
    String stripePaymentIntentId,
    List<BookingItemResponse> items,
    Instant createdAt
) {
}

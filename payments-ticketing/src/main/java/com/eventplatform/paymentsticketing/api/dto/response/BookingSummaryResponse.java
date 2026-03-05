package com.eventplatform.paymentsticketing.api.dto.response;

import com.eventplatform.paymentsticketing.domain.enums.BookingStatus;
import java.time.Instant;

public record BookingSummaryResponse(
    String bookingRef,
    Long slotId,
    BookingStatus status,
    Long totalAmount,
    String currency,
    Instant createdAt) {}

package com.eventplatform.paymentsticketing.api.dto.response;

import com.eventplatform.paymentsticketing.domain.enums.BookingStatus;

public record CancellationResponse(
    String bookingRef, BookingStatus status, RefundResponse refund) {}

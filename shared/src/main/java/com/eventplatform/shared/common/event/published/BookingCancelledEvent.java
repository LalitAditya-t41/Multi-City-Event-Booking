package com.eventplatform.shared.common.event.published;

import java.util.List;

/**
 * Published by payments-ticketing after a booking is cancelled (buyer-initiated or admin). Consumed
 * by booking-inventory to release confirmed seats back to AVAILABLE.
 *
 * <p>Rule 4 compliance: contains only primitives, IDs, enums, and value collections. reason values:
 * "BUYER_CANCEL", "ADMIN_CANCEL", "PAYMENT_FAILED"
 */
public record BookingCancelledEvent(
    Long bookingId,
    Long cartId,
    List<Long> seatIds,
    Long userId,
    String reason // "BUYER_CANCEL", "ADMIN_CANCEL", "PAYMENT_FAILED"
    ) {}

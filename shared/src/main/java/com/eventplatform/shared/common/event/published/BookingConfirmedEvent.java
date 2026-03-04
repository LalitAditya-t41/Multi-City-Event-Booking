package com.eventplatform.shared.common.event.published;

import java.util.List;

/**
 * Published by payments-ticketing after Stripe confirms the payment.
 * Consumed by booking-inventory to transition seat states to CONFIRMED.
 *
 * Rule 4 compliance: contains only primitives, IDs, strings, and value collections.
 */
public record BookingConfirmedEvent(
    Long cartId,
    List<Long> seatIds,
    String stripePaymentIntentId,   // was: ebOrderId — now carries the Stripe PaymentIntent ID
    Long userId
) {
}

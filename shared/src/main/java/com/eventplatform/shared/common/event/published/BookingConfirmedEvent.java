package com.eventplatform.shared.common.event.published;

import java.util.List;

/**
 * Published by payments-ticketing after Stripe confirms the payment. Consumed by: -
 * booking-inventory → transitions seat states to CONFIRMED - promotions → creates CouponRedemption
 * keyed by bookingId
 *
 * <p>Rule 4 compliance: contains only primitives, IDs, strings, and value collections.
 *
 * <p>CHANGE (FR7 pre-fix): added {@code bookingId} so promotions can FK-reference the confirmed
 * booking when recording coupon redemptions.
 */
public record BookingConfirmedEvent(
    Long bookingId,
    Long cartId,
    List<Long> seatIds,
    String stripePaymentIntentId, // was: ebOrderId — now carries the Stripe PaymentIntent ID
    Long userId) {}

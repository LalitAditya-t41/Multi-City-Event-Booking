package com.eventplatform.shared.common.event.published;

/**
 * Published by booking-inventory when cart lock is confirmed (HARD_LOCKED transition).
 * Consumed by payments-ticketing to create a Stripe PaymentIntent.
 *
 * Rule 4 compliance: contains only primitives, IDs, and Strings -- no @Entity objects.
 * couponCode may be null if no coupon was applied.
 */
public record CartAssembledEvent(
    Long cartId,
    Long slotId,
    Long userId,
    Long orgId,
    String ebEventId,
    String couponCode,                   // nullable -- null if no coupon applied
    long totalAmountInSmallestUnit,      // paise (for INR) -- passed to Stripe PaymentIntent `amount`
    String currency,                     // e.g. "inr" -- passed to Stripe PaymentIntent `currency`
    String userEmail                     // passed to Stripe PaymentIntent `receipt_email`
) {
}

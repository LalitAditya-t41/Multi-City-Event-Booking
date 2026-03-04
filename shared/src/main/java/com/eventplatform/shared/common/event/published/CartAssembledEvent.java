package com.eventplatform.shared.common.event.published;

/**
 * Published by booking-inventory when a cart is confirmed (HARD_LOCKED transition).
 * Consumed by payments-ticketing to initiate Eventbrite checkout widget display.
 *
 * Rule 4 compliance: contains only primitives, IDs, and Strings — no @Entity objects.
 *
 * couponCode may be null if no coupon was applied.
 */
public record CartAssembledEvent(
    Long cartId,
    Long slotId,
    Long userId,
    Long orgId,
    String ebEventId,
    String couponCode   // nullable
) {
}

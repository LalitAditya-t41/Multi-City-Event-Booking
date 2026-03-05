package com.eventplatform.shared.common.event.published;

/**
 * Published by promotions module after a coupon has been validated and applied to a cart. Consumed
 * by booking-inventory to store the coupon discount on the Cart entity so that
 * CartService.confirm() can subtract it from the Stripe PaymentIntent amount.
 *
 * <p>Rule 4 compliance: primitives, IDs, and strings only — no @Entity references.
 *
 * <p>Fields ------ cartId — the cart the coupon was applied to couponCode — the human-readable code
 * entered by the user discountAmountInSmallestUnit — already-computed discount (e.g. paise / cents)
 * currency — ISO-4217 lower-case (e.g. "inr", "usd") userId — identity of the user who applied the
 * coupon
 */
public record CouponAppliedEvent(
    Long cartId,
    String couponCode,
    long discountAmountInSmallestUnit,
    String currency,
    Long userId) {}

package com.eventplatform.shared.common.event.published;

/**
 * Published by promotions module after basic coupon validation passes
 * (code exists, not expired, usage limit not reached) but BEFORE the
 * coupon discount has been committed to the Cart.
 *
 * Downstream listeners can use this for audit logging or pre-flight checks.
 * The actual discount mutation on the Cart happens only after
 * {@link CouponAppliedEvent} is received.
 *
 * Rule 4 compliance: primitives, IDs, and strings only — no @Entity references.
 *
 * Fields
 * ------
 * cartId                       — the cart under validation
 * couponCode                   — the code entered by the user
 * discountAmountInSmallestUnit — projected discount (e.g. paise / cents)
 * currency                     — ISO-4217 lower-case
 * userId                       — identity of the user requesting validation
 */
public record CouponValidatedEvent(
    Long   cartId,
    String couponCode,
    long   discountAmountInSmallestUnit,
    String currency,
    Long   userId
) {
}

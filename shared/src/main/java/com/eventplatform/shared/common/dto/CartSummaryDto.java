package com.eventplatform.shared.common.dto;

import java.time.Instant;

/**
 * Cart-level header snapshot exposed via {@link
 * com.eventplatform.shared.common.service.CartSnapshotReader}.
 *
 * <p>Used by promotions.CouponEligibilityService to read the cart's organisational context (orgId,
 * slotId) and the coupon code already stored on the cart, without coupling the promotions module to
 * booking-inventory's @Entity types.
 *
 * <p>Rule 4 / shared contract: value object — no @Entity, no module imports.
 *
 * <p>Fields ------ cartId — primary key of the cart orgId — FK to the organiser who owns the show
 * slot slotId — FK to the show slot being booked couponCode — coupon code attached to the cart,
 * null if none expiresAt — cart TTL expiry timestamp currency — ISO-4217 lower-case currency for
 * this cart
 */
public record CartSummaryDto(
    Long cartId, Long orgId, Long slotId, String couponCode, Instant expiresAt, String currency) {}

package com.eventplatform.shared.common.service;

import com.eventplatform.shared.common.dto.CartItemSnapshotDto;
import com.eventplatform.shared.common.dto.CartSummaryDto;
import java.util.List;

/**
 * Cross-module read contract for cart data.
 * Implemented by booking-inventory's CartSnapshotReaderImpl.
 * Only shared/ DTOs are allowed in signatures — no module @Entity types.
 */
public interface CartSnapshotReader {

    /**
     * Returns the line-item contents of the given cart.
     * Used by payments-ticketing to build BookingItems.
     */
    List<CartItemSnapshotDto> getCartItems(Long cartId);

    /**
     * Returns cart-level header metadata (orgId, slotId, couponCode, etc.).
     * Used by promotions.CouponEligibilityService to validate eligibility
     * without importing booking-inventory types.
     *
     * @throws com.eventplatform.shared.common.exception.ResourceNotFoundException
     *         if no cart exists with the given id
     */
    CartSummaryDto getCartSummary(Long cartId);
}

package com.eventplatform.bookinginventory.service;

import com.eventplatform.bookinginventory.repository.CartItemRepository;
import com.eventplatform.bookinginventory.repository.CartRepository;
import com.eventplatform.shared.common.dto.CartItemSnapshotDto;
import com.eventplatform.shared.common.dto.CartSummaryDto;
import com.eventplatform.shared.common.exception.ResourceNotFoundException;
import com.eventplatform.shared.common.service.CartSnapshotReader;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CartSnapshotReaderImpl implements CartSnapshotReader {

  private final CartItemRepository cartItemRepository;
  private final CartRepository cartRepository;

  public CartSnapshotReaderImpl(
      CartItemRepository cartItemRepository, CartRepository cartRepository) {
    this.cartItemRepository = cartItemRepository;
    this.cartRepository = cartRepository;
  }

  @Override
  @Transactional(readOnly = true)
  public List<CartItemSnapshotDto> getCartItems(Long cartId) {
    return cartItemRepository.findByCartId(cartId).stream()
        .map(
            item ->
                new CartItemSnapshotDto(
                    item.getId(),
                    item.getSeatId(),
                    item.getGaClaimId(),
                    item.getEbTicketClassId(),
                    item.getBasePrice().amount().longValue(),
                    item.getBasePrice().currency().toLowerCase(),
                    item.getQuantity()))
        .toList();
  }

  /**
   * Returns cart-level header metadata required by promotions module for coupon eligibility checks.
   * Throws ResourceNotFoundException (404) if the cart does not exist so callers get a consistent
   * error contract.
   */
  @Override
  @Transactional(readOnly = true)
  public CartSummaryDto getCartSummary(Long cartId) {
    var cart =
        cartRepository
            .findById(cartId)
            .orElseThrow(
                () -> new ResourceNotFoundException("Cart not found: " + cartId, "CART_NOT_FOUND"));

    return new CartSummaryDto(
        cart.getId(),
        cart.getOrgId(),
        cart.getShowSlotId(),
        cart.getCouponCode(),
        cart.getExpiresAt(),
        cart.getGroupDiscountAmount().currency());
  }
}

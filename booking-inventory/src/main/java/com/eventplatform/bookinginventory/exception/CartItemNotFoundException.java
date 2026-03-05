package com.eventplatform.bookinginventory.exception;

import com.eventplatform.shared.common.exception.ResourceNotFoundException;

public class CartItemNotFoundException extends ResourceNotFoundException {
  public CartItemNotFoundException(Long itemId) {
    super("Cart item not found: " + itemId, "CART_ITEM_NOT_FOUND");
  }
}

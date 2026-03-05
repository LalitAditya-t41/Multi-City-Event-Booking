package com.eventplatform.bookinginventory.exception;

import com.eventplatform.shared.common.exception.ResourceNotFoundException;

public class CartNotFoundException extends ResourceNotFoundException {
  public CartNotFoundException(Long cartId) {
    super("Cart not found: " + cartId, "CART_NOT_FOUND");
  }
}

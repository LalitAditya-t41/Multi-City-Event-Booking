package com.eventplatform.bookinginventory.exception;

import com.eventplatform.shared.common.exception.BaseException;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;

public class CartExpiredException extends BaseException {
  public CartExpiredException(Long cartId, Instant expiredAt) {
    super(
        "Cart expired",
        "CART_EXPIRED",
        HttpStatus.GONE,
        Map.of("cartId", cartId, "expiredAt", expiredAt));
  }
}

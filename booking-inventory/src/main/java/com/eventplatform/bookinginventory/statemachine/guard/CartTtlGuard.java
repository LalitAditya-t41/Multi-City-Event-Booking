package com.eventplatform.bookinginventory.statemachine.guard;

import com.eventplatform.bookinginventory.domain.Cart;
import com.eventplatform.bookinginventory.domain.CartItem;
import com.eventplatform.bookinginventory.exception.CartExpiredException;
import com.eventplatform.bookinginventory.exception.SoftLockExpiredException;
import com.eventplatform.bookinginventory.service.redis.SeatLockRedisService;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class CartTtlGuard {

  private final SeatLockRedisService seatLockRedisService;

  public CartTtlGuard(SeatLockRedisService seatLockRedisService) {
    this.seatLockRedisService = seatLockRedisService;
  }

  public void assertCartConfirmable(Cart cart, List<CartItem> items, Long userId) {
    if (cart.isExpired(Instant.now())) {
      throw new CartExpiredException(cart.getId(), cart.getExpiresAt());
    }
    Set<Long> expired =
        items.stream()
            .map(CartItem::getSeatId)
            .filter(Objects::nonNull)
            .filter(seatId -> !seatLockRedisService.isOwnedBy(seatId, userId))
            .collect(Collectors.toSet());
    if (!expired.isEmpty()) {
      throw new SoftLockExpiredException(expired);
    }
  }
}

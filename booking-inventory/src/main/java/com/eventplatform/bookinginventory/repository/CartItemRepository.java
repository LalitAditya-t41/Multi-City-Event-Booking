package com.eventplatform.bookinginventory.repository;

import com.eventplatform.bookinginventory.domain.CartItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    Optional<CartItem> findByIdAndCartUserId(Long id, Long userId);

    boolean existsByCartIdAndSeatId(Long cartId, Long seatId);

    List<CartItem> findByCartId(Long cartId);

    Optional<CartItem> findFirstBySeatId(Long seatId);
}

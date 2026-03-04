package com.eventplatform.bookinginventory.repository;

import com.eventplatform.bookinginventory.domain.Cart;
import com.eventplatform.bookinginventory.domain.enums.CartStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CartRepository extends JpaRepository<Cart, Long> {

    @EntityGraph(attributePaths = "items")
    @Query("select c from Cart c where c.id = :id")
    Optional<Cart> findWithItemsById(@Param("id") Long id);

    Optional<Cart> findByUserIdAndShowSlotIdAndStatus(Long userId, Long showSlotId, CartStatus status);

    List<Cart> findByStatusAndExpiresAtBefore(CartStatus status, Instant now);
}

package com.eventplatform.bookinginventory.service;

import com.eventplatform.bookinginventory.repository.CartItemRepository;
import com.eventplatform.shared.common.dto.CartItemSnapshotDto;
import com.eventplatform.shared.common.service.CartSnapshotReader;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CartSnapshotReaderImpl implements CartSnapshotReader {

    private final CartItemRepository cartItemRepository;

    public CartSnapshotReaderImpl(CartItemRepository cartItemRepository) {
        this.cartItemRepository = cartItemRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CartItemSnapshotDto> getCartItems(Long cartId) {
        return cartItemRepository.findByCartId(cartId).stream()
            .map(item -> new CartItemSnapshotDto(
                item.getId(),
                item.getSeatId(),
                item.getGaClaimId(),
                item.getEbTicketClassId(),
                item.getBasePrice().amount().longValue(),
                item.getBasePrice().currency().toLowerCase(),
                item.getQuantity()
            ))
            .toList();
    }
}

package com.eventplatform.shared.common.service;

import com.eventplatform.shared.common.dto.CartItemSnapshotDto;
import java.util.List;

public interface CartSnapshotReader {
    List<CartItemSnapshotDto> getCartItems(Long cartId);
}

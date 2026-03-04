package com.eventplatform.bookinginventory.api.dto.response;

import com.eventplatform.bookinginventory.domain.enums.CartStatus;
import com.eventplatform.shared.common.domain.Money;
import com.eventplatform.shared.common.enums.SeatingMode;
import java.time.Instant;
import java.util.List;

public record CartResponse(
    Long cartId,
    Long slotId,
    CartStatus status,
    Instant expiresAt,
    SeatingMode seatingMode,
    List<CartItemResponse> items,
    Money subtotal,
    Money discountAmount,
    Money total
) {
}

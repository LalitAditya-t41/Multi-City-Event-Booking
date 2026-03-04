package com.eventplatform.bookinginventory.mapper;

import com.eventplatform.bookinginventory.api.dto.response.CartItemResponse;
import com.eventplatform.bookinginventory.api.dto.response.CartResponse;
import com.eventplatform.bookinginventory.domain.Cart;
import com.eventplatform.bookinginventory.domain.CartItem;
import com.eventplatform.bookinginventory.service.CartPricingService.CartPricingResult;
import com.eventplatform.shared.common.domain.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface CartMapper {

    default CartResponse toResponse(
        Cart cart,
        List<CartItem> items,
        CartPricingResult pricing,
        Map<Long, String> tierNames,
        Map<Long, String> seatNumbers
    ) {
        List<CartItemResponse> responses = items.stream()
            .map(item -> new CartItemResponse(
                item.getId(),
                item.getSeatId(),
                seatNumbers.get(item.getSeatId()),
                item.getPricingTierId(),
                tierNames.get(item.getPricingTierId()),
                item.getQuantity(),
                item.getBasePrice(),
                pricing.itemDiscounts().getOrDefault(item.getId(), new Money(BigDecimal.ZERO, item.getBasePrice().currency()))
            ))
            .toList();

        return new CartResponse(
            cart.getId(),
            cart.getShowSlotId(),
            cart.getStatus(),
            cart.getExpiresAt(),
            cart.getSeatingMode(),
            responses,
            pricing.subtotal(),
            pricing.discount(),
            cart.getCouponDiscountAmount(),
            pricing.total()
        );
    }
}

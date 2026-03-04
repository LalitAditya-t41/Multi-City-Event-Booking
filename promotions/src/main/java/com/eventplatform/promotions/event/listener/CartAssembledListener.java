package com.eventplatform.promotions.event.listener;

import com.eventplatform.promotions.service.CouponRedemptionService;
import com.eventplatform.shared.common.event.published.CartAssembledEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class CartAssembledListener {

    private final CouponRedemptionService couponRedemptionService;

    public CartAssembledListener(CouponRedemptionService couponRedemptionService) {
        this.couponRedemptionService = couponRedemptionService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCartAssembled(CartAssembledEvent event) {
        couponRedemptionService.onCartAssembled(event.cartId(), event.userId());
    }
}

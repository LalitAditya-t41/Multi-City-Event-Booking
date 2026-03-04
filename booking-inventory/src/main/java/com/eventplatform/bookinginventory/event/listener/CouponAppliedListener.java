package com.eventplatform.bookinginventory.event.listener;

import com.eventplatform.bookinginventory.repository.CartRepository;
import com.eventplatform.shared.common.event.published.CouponAppliedEvent;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class CouponAppliedListener {

    private static final Logger log = LoggerFactory.getLogger(CouponAppliedListener.class);

    private final CartRepository cartRepository;

    public CouponAppliedListener(CartRepository cartRepository) {
        this.cartRepository = cartRepository;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCouponApplied(CouponAppliedEvent event) {
        cartRepository.findById(event.cartId()).ifPresent(cart -> {
            if (!cart.getUserId().equals(event.userId())) {
                return;
            }
            BigDecimal amount = BigDecimal.valueOf(event.discountAmountInSmallestUnit())
                .divide(BigDecimal.valueOf(100));
            cart.setCouponCode(event.couponCode());
            cart.setCouponDiscountAmount(amount);
            cartRepository.save(cart);
            log.debug("Applied coupon discount to cart. cartId={} couponCode={} amount={}",
                event.cartId(), event.couponCode(), amount);
        });
    }
}

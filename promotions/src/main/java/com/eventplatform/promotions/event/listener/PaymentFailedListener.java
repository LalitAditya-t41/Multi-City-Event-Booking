package com.eventplatform.promotions.event.listener;

import com.eventplatform.promotions.service.CouponRedemptionService;
import com.eventplatform.shared.common.event.published.PaymentFailedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class PaymentFailedListener {

  private final CouponRedemptionService couponRedemptionService;

  public PaymentFailedListener(CouponRedemptionService couponRedemptionService) {
    this.couponRedemptionService = couponRedemptionService;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onPaymentFailed(PaymentFailedEvent event) {
    couponRedemptionService.onPaymentFailed(event.cartId(), event.userId());
  }
}

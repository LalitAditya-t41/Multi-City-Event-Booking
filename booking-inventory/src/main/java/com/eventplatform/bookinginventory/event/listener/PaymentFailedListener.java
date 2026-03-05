package com.eventplatform.bookinginventory.event.listener;

import com.eventplatform.bookinginventory.service.CartService;
import com.eventplatform.shared.common.event.published.PaymentFailedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class PaymentFailedListener {

  private final CartService cartService;

  public PaymentFailedListener(CartService cartService) {
    this.cartService = cartService;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onPaymentFailed(PaymentFailedEvent event) {
    cartService.onPaymentFailed(event.cartId(), event.seatIds(), event.userId());
  }
}

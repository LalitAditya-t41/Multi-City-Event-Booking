package com.eventplatform.bookinginventory.event.listener;

import com.eventplatform.bookinginventory.service.CartService;
import com.eventplatform.shared.common.event.published.BookingConfirmedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class BookingConfirmedListener {

  private final CartService cartService;

  public BookingConfirmedListener(CartService cartService) {
    this.cartService = cartService;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onBookingConfirmed(BookingConfirmedEvent event) {
    cartService.onBookingConfirmed(
        event.cartId(), event.seatIds(), event.stripePaymentIntentId(), event.userId());
  }
}

package com.eventplatform.promotions.event.listener;

import com.eventplatform.promotions.service.CouponRedemptionService;
import com.eventplatform.shared.common.event.published.BookingConfirmedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class BookingConfirmedListener {

  private final CouponRedemptionService couponRedemptionService;

  public BookingConfirmedListener(CouponRedemptionService couponRedemptionService) {
    this.couponRedemptionService = couponRedemptionService;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onBookingConfirmed(BookingConfirmedEvent event) {
    couponRedemptionService.onBookingConfirmed(event.bookingId(), event.cartId(), event.userId());
  }
}

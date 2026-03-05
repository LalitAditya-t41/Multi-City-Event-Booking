package com.eventplatform.promotions.event.listener;

import com.eventplatform.promotions.service.CouponRedemptionService;
import com.eventplatform.shared.common.event.published.BookingCancelledEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component("promotionsBookingCancelledListener")
public class BookingCancelledListener {

  private final CouponRedemptionService couponRedemptionService;

  public BookingCancelledListener(CouponRedemptionService couponRedemptionService) {
    this.couponRedemptionService = couponRedemptionService;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onBookingCancelled(BookingCancelledEvent event) {
    couponRedemptionService.onBookingCancelled(event.bookingId());
  }
}
